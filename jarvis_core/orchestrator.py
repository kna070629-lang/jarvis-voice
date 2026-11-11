"""jarvis_core.orchestrator — The central JARVIS core.

The core is the only part that decides what happens. It plans the work,
picks the agents, authorises the actions through the security engine,
verifies what came back and combines the pieces into one answer.

An agent never calls another agent. Everything goes through the core, so the
flow of a request is always visible and always controlled.
"""

from __future__ import annotations

import logging
import threading
import time
import uuid

from .clusters import build_clusters, RepositoryContext, WorkflowLibrary
from .context import ContextStore
from .engines.comms import CommunicationEngine
from .engines.memory import MemoryEngine
from .engines.model_router import ModelRouter
from .engines.planner import PlannerEngine
from .engines.security import SecurityEngine
from .engines.verification import VerificationEngine
from .events import ConnectionTracker, EventBus
from .messages import Message, NodeState, Status
from .registry import AgentRegistry
from .github import GitHubClient, find_token
from .services import ServiceRegistry
from .topology import CORE_ID, CORE_NAME, all_links, all_nodes, node_name


log = logging.getLogger("jarvis.core")


class JarvisCore:
    """The central intelligence."""

    def __init__(
        self,
        settings=None,
        providers: list | None = None,
        search=None,
        repository_root: str = "",
        granted_permissions: set[str] | None = None,
        bridge_url: str = "",
        github_repository: str = "",
    ):
        self.settings = settings
        self.registry = AgentRegistry()
        self.events = EventBus()
        self.tracker = ConnectionTracker()
        self.context = ContextStore()
        self.comms = CommunicationEngine(
            context_store=self.context, bus=self.events, tracker=self.tracker
        )
        self.memory = MemoryEngine()
        self.model_router = ModelRouter(providers or [])
        self.security = SecurityEngine(granted_permissions)
        self.verification = VerificationEngine()
        self.planner = PlannerEngine(memory=self.memory, model_router=self.model_router)
        self.search = search
        self.workflows = WorkflowLibrary()
        self.github = GitHubClient(
            token=find_token(repository_root),
            repository=github_repository or "",
        )
        self.services = ServiceRegistry(
            bridge_url=bridge_url or "",
            repository_root=repository_root or "",
        )
        self.development_repository = RepositoryContext(repository_root) if repository_root else None
        self.repository_root = repository_root

        self._lock = threading.RLock()
        self._tasks_run = 0
        self._tasks_failed = 0
        self._started = time.time()

        self._declare_topology()
        self._register_engines()
        self._register_clusters()
        self._register_routes()

    # -- construction -----------------------------------------------------
    def _declare_topology(self) -> None:
        for source, destination in all_links():
            self.tracker.declare(source, destination)
        # The core is a node in the network, so its state is visible too.
        self.registry.ensure_node(CORE_ID, CORE_NAME, kind="core")

    def _register_engines(self) -> None:
        """The engines are nodes in the network too, so they can be watched."""
        for node_id, name in [
            ("engine.memory", "Memory Engine"),
            ("engine.planner", "Task Planning Engine"),
            ("engine.model_router", "Model Router"),
            ("engine.security", "Security Engine"),
            ("engine.verification", "Verification Engine"),
            ("engine.comms", "Context and Communication Engine"),
        ]:
            self.registry.register(_EngineNode(self, node_id, name))

    def _register_clusters(self) -> None:
        self.registry.register_all(build_clusters(self))

    def _register_routes(self) -> None:
        """Only the core is a valid destination for an agent."""
        self.comms.register_route(CORE_ID, self._receive_from_agent)

    # -- the main entry point ---------------------------------------------
    def execute(self, request: str, context_reference: str = "") -> dict:
        """Run a request through the whole system."""
        started = time.perf_counter()
        request = str(request or "").strip()
        if not request:
            return self._result("", [], "the request was empty", 0)

        # The core is the only node that works while a request is running.
        self.registry.set_status(CORE_ID, Status.PROCESSING, "orchestrating")

        reference = context_reference or self.comms.new_context()
        self.memory.record("user", request, reference)

        plan = self.planner.plan(request, reference)
        self.registry.set_status("engine.planner", Status.COMPLETED, f"{len(plan.graph.all())} tasks")

        results: list[dict] = []
        answer = ""
        action_request = None
        pending_approval = None
        error = ""

        try:
            for task in self._execution_order(plan.graph):
                outcome = self._run_task(task, reference)
                results.append(outcome)
                if outcome["status"] == Status.FAILED:
                    error = outcome.get("error", "")
                    # The agent still said something useful, so it is spoken.
                    if outcome.get("result"):
                        answer = str(outcome["result"])
                    break
                if outcome.get("action_request"):
                    action_request = outcome["action_request"]
                    if outcome.get("requires_confirmation"):
                        pending_approval = outcome
                    break
                if outcome.get("result") and not answer:
                    answer = str(outcome["result"])
        except Exception as exc:
            log.exception("The core failed while running a request")
            error = type(exc).__name__

        # An action result is not an empty answer. It is handed to the
        # companion, so the spoken line is produced here.
        if action_request is not None and not answer:
            answer = self._spoken_line(action_request)

        # The verification engine checks what came back. An action is verified
        # on its own terms, not as a spoken answer.
        if action_request is None:
            verification = self.verification.verify(answer, [], "answer")
            if not verification.ok and verification.issues:
                error = error or verification.issues[0]

        if answer:
            self.memory.record("assistant", answer, reference)
        self.registry.set_status(CORE_ID, Status.IDLE, "idle")

        duration = int((time.perf_counter() - started) * 1000)
        return self._result(
            answer,
            results,
            error,
            duration,
            action_request=action_request,
            pending_approval=pending_approval,
            context_reference=reference,
        )

    def _spoken_line(self, action_request: dict) -> str:
        """The short sentence said while an action runs on the phone."""
        action = action_request.get("action", "")
        args = action_request.get("args") or {}
        if action_request.get("requires_confirmation"):
            question = {
                "call": "Shall I call %s?",
                "call_contact": "Shall I call %s?",
                "prepare_sms": "Shall I prepare the message for %s?",
                "prepare_whatsapp": "Shall I open WhatsApp for %s?",
                "open_chat": "Shall I open the chat with %s?",
                "read_notifications": "Shall I read your notifications?",
            }.get(action)
            if question:
                target = args.get("number") or args.get("name") or "that contact"
                return question % target if "%s" in question else question
            return "Shall I do that?"
        target = (
            args.get("name")
            or args.get("package")
            or args.get("number")
            or args.get("duration")
            or args.get("time")
            or args.get("level")
            or args.get("state")
            or args.get("argument")
            or ""
        )
        templates = {
            "open_app": "Opening %s.",
            "open_package": "Opening %s.",
            "open_settings": "Opening settings.",
            "open_dialer": "Opening the dialer.",
            "set_alarm": "Setting the alarm for %s.",
            "set_timer": "Setting a timer for %s minutes.",
            "set_volume": "Setting the volume to %s.",
            "media_control": "Playing.",
            "toggle_flashlight": "Turning the flashlight %s.",
            "prepare_whatsapp": "Opening WhatsApp for %s.",
            "prepare_sms": "Preparing the message for %s.",
            "read_notifications": "Reading your notifications.",
        }
        template = templates.get(action, "Done.")
        if "%s" in template and target:
            return template % target
        return template

    def _execution_order(self, graph) -> list:
        """The tasks in dependency order."""
        order = []
        seen: set[str] = set()

        def visit(task_id: str) -> None:
            if task_id in seen:
                return
            seen.add(task_id)
            task = graph.get(task_id)
            if task is None:
                return
            for dependency in task.depends_on:
                visit(dependency)
            order.append(task)

        visit(graph.root)
        for task in graph.all():
            visit(task.task_id)
        return order

    def _run_task(self, task, reference: str) -> dict:
        """Run one task through its agent."""
        agent = self.registry.get(task.agent_id)
        if agent is None:
            return {
                "task_id": task.task_id,
                "task_type": task.task_type,
                "status": Status.FAILED,
                "error": f"no agent for {task.task_type}",
                "result": "",
            }

        task.status = "running"
        self.registry.set_status(agent.id, Status.EXECUTING, task.description, task.task_id)

        # The agent is told what it is allowed to do, nothing more.
        message = Message(
            task_id=task.task_id,
            agent_id=agent.id,
            source=CORE_ID,
            destination=agent.id,
            task_type=task.task_type,
            context_reference=reference,
            permissions=list(task.permissions),
            payload=dict(task.payload),
            parent_task_id=task.parent_task_id,
            priority=task.priority,
        )
        message._reference = reference  # type: ignore[attr-defined]
        message._request = task.description  # type: ignore[attr-defined]

        self.tracker.set_status(CORE_ID, agent.id, "transmitting", message)

        started = time.perf_counter()
        try:
            reply = agent.handle(message, self)
        except Exception as exc:
            duration = int((time.perf_counter() - started) * 1000)
            self.tracker.record_failure(CORE_ID, agent.id)
            self.registry.set_status(agent.id, Status.FAILED, "the agent failed", task.task_id, type(exc).__name__)
            self.registry.record_completion(agent.id, task.task_id, False, type(exc).__name__)
            log.exception("The agent %s failed", agent.id)
            return {
                "task_id": task.task_id,
                "task_type": task.task_type,
                "agent_id": agent.id,
                "status": Status.FAILED,
                "error": type(exc).__name__,
                "result": "",
                "duration_ms": duration,
            }
        duration = int((time.perf_counter() - started) * 1000)
        reply.duration_ms = duration

        self.tracker.settle(CORE_ID, agent.id, reply.status != Status.FAILED)
        self.registry.set_status(
            agent.id,
            Status.IDLE,
            "",
            task.task_id,
            reply.error if reply.status == Status.FAILED else "",
        )
        self.registry.record_completion(
            agent.id,
            task.task_id,
            reply.status != Status.FAILED,
            str(reply.result)[:200],
        )

        with self._lock:
            self._tasks_run += 1
            if reply.status == Status.FAILED:
                self._tasks_failed += 1

        # The result goes into the shared context for the next task.
        if reply.status != Status.FAILED and reply.result:
            self.comms.share(reference, {"last_result": str(reply.result)[:2000]})

        action_request = None
        requires_confirmation = False
        if isinstance(reply.result, dict) and "action_request" in reply.result:
            action_request = reply.result["action_request"]
            requires_confirmation = bool(reply.result.get("requires_confirmation"))

        return {
            "task_id": task.task_id,
            "task_type": task.task_type,
            "agent_id": agent.id,
            "status": reply.status,
            "error": reply.error,
            "result": reply.result if not isinstance(reply.result, dict) else "",
            "evidence": reply.evidence,
            "action_request": action_request,
            "requires_confirmation": requires_confirmation,
            "duration_ms": duration,
        }

    def _receive_from_agent(self, message: Message) -> Message:
        """The route every agent answers to."""
        reply = message.copy()
        reply.destination = CORE_ID
        reply.status = Status.COMPLETED
        return reply

    def _result(
        self,
        answer: str,
        results: list,
        error: str,
        duration_ms: int,
        action_request=None,
        pending_approval=None,
        context_reference: str = "",
    ) -> dict:
        return {
            "answer": answer,
            "error": error,
            "duration_ms": duration_ms,
            "trace": results,
            "action_request": action_request,
            "pending_approval": pending_approval,
            "context_reference": context_reference,
            "provider": self.model_router.last_provider,
            "model": self.model_router.last_model,
        }

    # -- observation ------------------------------------------------------
    def note_activity(
        self,
        tier: str,
        duration_ms: int,
        provider: str = "",
        error: str = "",
    ) -> None:
        """Record what happened during a request.

        The fast path in the bridge handles most requests without going
        through the core, so the core is told what happened. That keeps the
        network view honest without putting every request through the
        slower orchestration path.
        """
        self.registry.set_status(CORE_ID, Status.IDLE, f"{tier} in {duration_ms} ms")
        self.registry.set_status(
            "engine.model_router",
            Status.IDLE,
            provider or "no model used",
        )
        if provider:
            self.registry.record_completion(
                "engine.model_router", f"chat_{int(time.time())}", True, provider
            )
        if error:
            self.registry.set_status(CORE_ID, Status.FAILED, error)
            self.tracker.record_failure(CORE_ID, "engine.model_router")

    def topology(self) -> dict:
        """The live network: the nodes, their state and the links."""
        nodes = []
        for node in all_nodes():
            state = self.registry.state(node["node_id"])
            entry = dict(node)
            if state is not None:
                entry.update({
                    "status": state.status,
                    "detail": state.detail,
                    "capabilities": list(state.capabilities),
                    "tasks_completed": state.tasks_completed,
                    "tasks_failed": state.tasks_failed,
                    "last_task_id": state.last_task_id,
                    "last_error": state.last_error,
                    "recent_tasks": list(state.recent_tasks),
                })
            else:
                entry.update({
                    "status": Status.IDLE,
                    "detail": "",
                    "capabilities": [],
                    "tasks_completed": 0,
                    "tasks_failed": 0,
                    "last_task_id": "",
                    "last_error": "",
                    "recent_tasks": [],
                })
            nodes.append(entry)

        # The external services are real nodes with the state their check found.
        service_nodes = []
        for report in self.services.reports():
            service_nodes.append({
                "node_id": "service." + report.service_id,
                "kind": "service",
                "name": report.name,
                "cluster": "external",
                "role": report.purpose[:60],
                "status": report.state,
                "detail": report.detail,
                "capabilities": list(report.actions),
                "tasks_completed": 0,
                "tasks_failed": 0,
                "last_task_id": "",
                "last_error": report.detail if report.state in ("error", "disconnected") else "",
                "recent_tasks": list(report.history),
                "position": [],
            })
        nodes.extend(service_nodes)

        return {
            "nodes": nodes,
            "links": [link.to_dict() for link in self.tracker.links()],
            "services": [report.to_dict() for report in self.services.reports()],
            "service_summary": self.services.summary(),
            "stats": self.stats(),
        }

    def stats(self) -> dict:
        with self._lock:
            return {
                "uptime_seconds": int(time.time() - self._started),
                "tasks_run": self._tasks_run,
                "tasks_failed": self._tasks_failed,
                "agents": len(self.registry.all()),
                "memory": self.memory.state(),
                "model_router": self.model_router.stats(),
                "security": self.security.stats(),
                "verification": self.verification.stats(),
                "comms": self.comms.stats(),
                "services": self.services.summary(),
            }

    def recent_messages(self, limit: int = 30) -> list[dict]:
        return self.comms.recent_messages(limit)

    def check_services(self) -> dict:
        """Test every external service for real."""
        self.services.check_all()
        return self.services.summary()

    def node_detail(self, node_id: str) -> dict:
        """Everything known about one node, for the inspection panel."""
        state = self.registry.state(node_id)
        if state is None:
            return {"node_id": node_id, "found": False}
        detail = state.to_dict()
        detail["found"] = True
        detail["name"] = node_name(node_id)
        detail["links"] = [
            link.to_dict()
            for link in self.tracker.links()
            if node_id in (link.source, link.destination)
        ]
        return detail


class _EngineNode:
    """A node in the network that represents one of the inner engines."""

    def __init__(self, core: JarvisCore, node_id: str, name: str):
        self.core = core
        self.id = node_id
        self.name = name
        self.cluster = "inner"
        self.capabilities = _ENGINE_CAPABILITIES.get(node_id, [])
        self.permissions: list[str] = []

    def handle(self, message: Message, context) -> Message:
        """The engines are driven by the core, so they answer directly."""
        reply = message.copy()
        reply.agent_id = self.id
        reply.source = self.id
        reply.destination = CORE_ID
        reply.status = Status.COMPLETED
        return reply


_ENGINE_CAPABILITIES = {
    "engine.memory": ["recall", "remember", "context"],
    "engine.planner": ["decompose", "schedule", "plan"],
    "engine.model_router": ["complete", "stream", "fallback"],
    "engine.security": ["authorise", "risk", "confirm"],
    "engine.verification": ["verify", "evidence", "confidence"],
    "engine.comms": ["route", "share", "aggregate"],
}
