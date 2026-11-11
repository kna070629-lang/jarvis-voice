"""jarvis_core.clusters — The six specialist clusters.

Each cluster is a group of agents that work in one area. The agents do real
work: the research cluster really searches, the device cluster really builds
a device action, and the voice cluster really answers through the model.

The clusters do not each run their own model. They share the model router, so
the number of providers stays small and the fallback behaviour is uniform.
"""

from __future__ import annotations

import logging
import subprocess
import time
from typing import Any

from .messages import Message, Status
from .registry import Agent


log = logging.getLogger("jarvis.core.clusters")


# The action type the companion understands for each kind of task.
_ACTION_TYPE = {
    "launch": "open_app",
    "call": "call",
    "message": "prepare_whatsapp",
    "alarm": "set_alarm",
    "timer": "set_timer",
    "volume": "set_volume",
    "media": "media_control",
    "flashlight": "toggle_flashlight",
    "notify": "read_notifications",
}


class ClusterAgent(Agent):
    """Base class that gives an agent access to the shared services."""

    def __init__(self, core):
        self.core = core
        self.id = self.id
        self.name = self.name
        self.cluster = self.cluster

    # -- helpers -----------------------------------------------------------
    def model(self) -> str:
        """Ask the shared model router."""
        router = self.core.model_router
        return router.complete(self._prompt(), self.core.comms.read(self._context_ref()) and self._context_text())

    def model_stream(self, on_delta=None) -> dict:
        router = self.core.model_router
        return router.complete_stream(self._prompt(), self._context_text(), on_delta)

    def _context_ref(self) -> str:
        return getattr(self, "_reference", "")

    def _context_text(self) -> str:
        values = self.core.comms.read(self._context_ref())
        if not values:
            return ""
        parts = []
        for key, value in values.items():
            if isinstance(value, (str, int, float)):
                parts.append(f"{key}: {value}")
            elif isinstance(value, list):
                parts.append(f"{key}: " + "; ".join(str(item) for item in value[:8]))
        return "\n".join(parts)[:6000]

    def _prompt(self) -> str:
        return getattr(self, "_request", "")

    def evidence(self, kind: str, detail: str, **extra) -> dict:
        record = {"kind": kind, "detail": detail, "at": time.time()}
        record.update(extra)
        return record


# ---------------------------------------------------------------- cluster 1
class VoiceConversationAgent(ClusterAgent):
    """Answers in plain speech."""

    id = "cluster.voice"
    name = "Voice and Conversation"
    cluster = "cluster.voice"
    role = "LUNA"
    capabilities = ["answer", "conversation", "time", "date", "speak"]
    permissions: list[str] = []

    def handle(self, message: Message, context) -> Message:
        request = message.payload.get("request") or ""
        reply = message.copy()
        reply.agent_id = self.id
        reply.source = self.id
        reply.destination = "core"
        reply.status = Status.COMPLETED

        if message.task_type == "time":
            now = time.localtime()
            reply.result = time.strftime("It is %-I:%M %p.", now)
            reply.evidence.append(self.evidence("clock", "read from the device clock"))
            return reply
        if message.task_type == "date":
            now = time.localtime()
            reply.result = time.strftime("Today is %A, %B %-d, %Y.", now)
            reply.evidence.append(self.evidence("clock", "read from the device clock"))
            return reply

        answer = self.core.model_router.complete(request, self._context_text())
        if not answer["text"]:
            reply.status = Status.FAILED
            reply.error = answer.get("error", "no provider could answer")
            reply.result = "I could not get an answer just now."
            return reply
        reply.result = answer["text"]
        reply.evidence.append(self.evidence(
            "model",
            f"answered by {answer['provider']}",
            provider=answer["provider"],
            model=answer.get("model", ""),
            duration_ms=answer.get("duration_ms", 0),
        ))
        return reply


# ---------------------------------------------------------------- cluster 2
class ResearchAgent(ClusterAgent):
    """Searches the web and summarises what it found."""

    id = "cluster.research"
    name = "Research and Web"
    cluster = "cluster.research"
    role = "VEGA"
    capabilities = ["research", "search", "summarise", "fact_check"]
    permissions: list[str] = []

    def handle(self, message: Message, context) -> Message:
        query = message.payload.get("argument") or message.payload.get("request") or ""
        reply = message.copy()
        reply.agent_id = self.id
        reply.source = self.id
        reply.destination = "core"
        reply.status = Status.COMPLETED

        if not query:
            reply.status = Status.FAILED
            reply.error = "no search query"
            reply.result = "I need something to look up."
            return reply

        try:
            results = self.core.search.search(query)
        except Exception as exc:
            reply.status = Status.FAILED
            reply.error = type(exc).__name__
            reply.result = "The live search failed."
            return reply

        if not results:
            reply.result = "The search finished but found nothing."
            reply.evidence.append(self.evidence("search", "no results", query=query))
            return reply

        sources = [
            f"{index}. {item.title}: {item.snippet}"
            for index, item in enumerate(results[:5], 1)
        ]
        joined = "\n".join(sources)
        prompt = (
            "Answer the request using only these search results. State that they are "
            "search results and include the source URLs. Do not claim broader research.\n\n"
            f"Request: {query}\n\nResults:\n{joined}\n\n"
            f"URLs:\n" + "\n".join(item.url for item in results[:5])
        )
        answer = self.core.model_router.complete(prompt, self._context_text())
        text = answer["text"] or joined
        reply.result = text
        for index, item in enumerate(results[:5], 1):
            reply.evidence.append(self.evidence("source", item.title, url=item.url, rank=index))
        return reply


# ---------------------------------------------------------------- cluster 3
class DevelopmentAgent(ClusterAgent):
    """Reads and reasons about code."""

    id = "cluster.development"
    name = "Development and Coding"
    cluster = "cluster.development"
    role = "ORION"
    capabilities = ["review", "analyse", "debug", "generate", "test"]
    permissions: list[str] = []

    def handle(self, message: Message, context) -> Message:
        request = message.payload.get("request") or ""
        reply = message.copy()
        reply.agent_id = self.id
        reply.source = self.id
        reply.destination = "core"
        reply.status = Status.COMPLETED

        # A repository summary is gathered for real when one is configured.
        repository = self.core.development_repository
        notes = ""
        if repository:
            try:
                notes = repository.summary()
            except Exception:
                notes = ""
        # The development intelligence reads the real repository when it can.
        github = getattr(self.core, "github", None)
        context_lines: list[str] = []
        if notes:
            context_lines.append(notes)
        if github is not None and github.installed():
            try:
                info = github.repository_info()
                context_lines.append(
                    f"GitHub: {info['name']} default branch {info['default_branch']}, "
                    f"{info['open_issues']} open issues, pushed {info['pushed_at']}"
                )
                for commit in github.commits(5):
                    context_lines.append(f"commit {commit['sha']} {commit['message']}")
                reply.evidence.append(self.evidence(
                    "github",
                    f"read {info['name']} from GitHub",
                    repository=info["name"],
                ))
            except Exception as exc:
                reply.evidence.append(self.evidence(
                    "github",
                    f"the repository could not be read ({type(exc).__name__})",
                ))

        prompt = request
        if context_lines:
            prompt = f"{request}\n\nRepository context:\n" + "\n".join(context_lines)
        answer = self.core.model_router.complete(prompt, self._context_text())
        if not answer["text"]:
            reply.status = Status.FAILED
            reply.error = answer.get("error", "no provider could answer")
            reply.result = "I could not get a code answer just now."
            return reply
        reply.result = answer["text"]
        reply.evidence.append(self.evidence(
            "model",
            f"answered by {answer['provider']}",
            provider=answer["provider"],
            repository=bool(notes),
        ))
        return reply


# ---------------------------------------------------------------- cluster 4
class DeviceControlAgent(ClusterAgent):
    """Builds the device actions the companion can run."""

    id = "cluster.device"
    name = "Android and Device Control"
    cluster = "cluster.device"
    role = "TITAN"
    capabilities = ["launch", "call", "message", "alarm", "volume", "media", "flashlight", "notify"]
    permissions: list[str] = ["android.permission.READ_CONTACTS", "android.permission.CALL_PHONE"]

    def handle(self, message: Message, context) -> Message:
        reply = message.copy()
        reply.agent_id = self.id
        reply.source = self.id
        reply.destination = "core"
        reply.status = Status.COMPLETED

        action_type = message.task_type
        argument = message.payload.get("argument") or ""

        action = _ACTION_TYPE.get(action_type)
        # A call to something that is not a number is a call to a contact, and
        # the device resolves the name.
        if action_type == "call":
            digits = "".join(ch for ch in str(argument) if ch.isdigit())
            action = "call" if 7 <= len(digits) <= 15 else "call_contact"
        request = {
            "call": {"number": argument, "name": argument},
            "call_contact": {"name": argument},
            "message": _message_arguments(argument),
            "launch": {"name": argument},
            "alarm": {"time": argument},
            "timer": {"duration": argument},
            "volume": {"level": argument},
            "media": {"action": argument},
            "flashlight": {"state": argument},
            "notify": {},
        }.get(action_type)

        if request is None:
            reply.result = "That device action is not supported."
            reply.status = Status.FAILED
            return reply

        # The security engine decides whether the user has to confirm it.
        decision = self.core.security.check(action or action_type, [], request)
        reply.permissions = list(decision.missing_permissions)
        if not decision.allowed:
            reply.status = Status.FAILED
            reply.error = decision.reason
            reply.result = decision.reason or "That action was refused."
            reply.evidence.append(self.evidence("security", decision.reason, risk=decision.risk))
            return reply

        # A device action is handed to the companion, which runs it on the
        # phone. The backend never claims it happened.
        reply.result = {
            "action_request": {
                "action": action or action_type,
                "args": request,
                "requires_confirmation": decision.requires_confirmation,
                "sensitive": decision.risk in ("medium", "high", "critical"),
            },
            "requires_confirmation": decision.requires_confirmation,
        }
        reply.evidence.append(self.evidence(
            "security",
            f"authorised as {decision.risk} risk",
            risk=decision.risk,
            requires_confirmation=decision.requires_confirmation,
        ))
        return reply


# ---------------------------------------------------------------- cluster 5
class AutomationAgent(ClusterAgent):
    """Runs a workflow of steps in order."""

    id = "cluster.automation"
    name = "Automation and Tools"
    cluster = "cluster.automation"
    role = "ATLAS"
    capabilities = ["workflow", "tool", "coordinate"]
    permissions: list[str] = []

    def handle(self, message: Message, context) -> Message:
        request = message.payload.get("request") or ""
        reply = message.copy()
        reply.agent_id = self.id
        reply.source = self.id
        reply.destination = "core"
        reply.status = Status.COMPLETED

        steps = self.core.workflows.steps_for(request)
        if not steps:
            answer = self.core.model_router.complete(request, self._context_text())
            reply.result = answer["text"] or "I could not work out how to do that."
            reply.evidence.append(self.evidence("model", "no workflow matched"))
            return reply

        performed = []
        for step in steps:
            decision = self.core.security.check(step.get("action", ""), step.get("permissions", []), step)
            if not decision.allowed:
                reply.status = Status.FAILED
                reply.error = decision.reason
                reply.result = decision.reason or "A step of the workflow was refused."
                reply.evidence.append(self.evidence("security", decision.reason, step=step.get("action", "")))
                return reply
            performed.append(step)
            reply.evidence.append(self.evidence("workflow_step", step.get("action", "")))

        reply.result = {
            "steps": performed,
            "note": "the companion runs these steps on the phone",
        }
        return reply


# ---------------------------------------------------------------- cluster 6
class KnowledgeAgent(ClusterAgent):
    """Answers from what JARVIS knows and remembers."""

    id = "cluster.knowledge"
    name = "Knowledge and Learning"
    cluster = "cluster.knowledge"
    role = "VEGA"
    capabilities = ["knowledge", "recall", "learn", "enrich"]
    permissions: list[str] = []

    def handle(self, message: Message, context) -> Message:
        request = message.payload.get("request") or ""
        reply = message.copy()
        reply.agent_id = self.id
        reply.source = self.id
        reply.destination = "core"
        reply.status = Status.COMPLETED

        known = self.core.memory.retrieve(request, limit=5)
        facts = [fact["fact"] for fact in self.core.memory.facts()]
        prompt = request
        if known:
            prompt += "\n\nWhat JARVIS already knows:\n" + "\n".join("- " + item for item in known)
        answer = self.core.model_router.complete(prompt, self._context_text())
        reply.result = answer["text"] or "I do not have anything on that yet."
        if known:
            for item in known:
                reply.evidence.append(self.evidence("memory", item))
        if answer.get("provider"):
            reply.evidence.append(self.evidence("model", f"answered by {answer['provider']}"))
        return reply


class WorkflowLibrary:
    """A small library of workflows the automation cluster can run."""

    def __init__(self):
        self._workflows: list[dict] = []

    def add(self, name: str, match: str, steps: list[dict]) -> None:
        self._workflows.append({"name": name, "match": match.lower(), "steps": steps})

    def steps_for(self, request: str) -> list[dict]:
        text = str(request).lower()
        for workflow in self._workflows:
            if workflow["match"] in text:
                return list(workflow["steps"])
        return []

    def names(self) -> list[str]:
        return [workflow["name"] for workflow in self._workflows]


class RepositoryContext:
    """Gives the development cluster a real view of the repository."""

    def __init__(self, root: str):
        self.root = root

    def summary(self, limit: int = 40) -> str:
        import os

        try:
            names = sorted(os.listdir(self.root))[:limit]
        except OSError:
            return ""
        python_files = [name for name in names if name.endswith(".py")]
        android_files = [name for name in names if name.endswith(".kt")]
        return (
            f"repository {self.root}\n"
            f"python modules: {', '.join(python_files)}\n"
            f"kotlin sources: {', '.join(android_files)}"
        )


class QualityAssuranceAgent(ClusterAgent):
    """Runs the real checks and reports what actually happened.

    The agent never claims a suite passed. It runs the command, reads the
    exit code and the summary, and reports that.
    """

    id = "cluster.qa"
    name = "Quality Assurance"
    cluster = "cluster.qa"
    role = "NOVA"
    capabilities = ["test", "verify", "audit", "quality"]
    permissions: list[str] = []

    def handle(self, message: Message, context) -> Message:
        request = message.payload.get("request") or ""
        reply = message.copy()
        reply.agent_id = self.id
        reply.source = self.id
        reply.destination = "core"
        reply.status = Status.COMPLETED

        root = getattr(self.core, "repository_root", "")
        if not root:
            reply.status = Status.FAILED
            reply.error = "no repository"
            reply.result = "I do not know where the repository is."
            return reply

        command = ["python3", "-m", "pytest", "tests/", "-q"]
        try:
            completed = subprocess.run(
                command,
                cwd=root,
                capture_output=True,
                text=True,
                timeout=300,
            )
        except Exception as exc:
            reply.status = Status.FAILED
            reply.error = type(exc).__name__
            reply.result = f"The test run could not start: {exc}"
            return reply

        output = (completed.stdout or "") + (completed.stderr or "")
        tail = "\n".join(output.strip().splitlines()[-4:])
        passed = completed.returncode == 0
        reply.result = (
            f"The backend test suite passed.\n{tail}"
            if passed else
            f"The backend test suite failed with exit code {completed.returncode}.\n{tail}"
        )
        reply.evidence.append(self.evidence(
            "test_run",
            f"pytest exit code {completed.returncode}",
            command=" ".join(command),
            exit_code=completed.returncode,
        ))
        if not passed:
            reply.status = Status.FAILED
            reply.error = "the test suite failed"
        return reply


def _message_arguments(argument: str) -> dict:
    """Split "to 919876543210 saying hello" into the number and the text."""
    text = str(argument or "").strip()
    number = ""
    body = ""
    lowered = text.lower()
    for marker in (" saying ", " that says ", " to say "):
        if marker in lowered:
            head, _, tail = lowered.partition(marker)
            number = "".join(ch for ch in head if ch.isdigit())
            body = text[len(head) + len(marker):].strip()
            break
    if not number:
        digits = "".join(ch for ch in text if ch.isdigit())
        if 7 <= len(digits) <= 15:
            number = digits
    if not body:
        body = text
    return {"number": number, "text": body}


def build_clusters(core) -> list[Agent]:
    """Create the six specialist clusters."""
    return [
        VoiceConversationAgent(core),
        ResearchAgent(core),
        DevelopmentAgent(core),
        DeviceControlAgent(core),
        AutomationAgent(core),
        KnowledgeAgent(core),
        QualityAssuranceAgent(core),
    ]
