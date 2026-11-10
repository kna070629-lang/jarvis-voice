import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

from ai_provider import (
    AIReply,
    AllProvidersFailed,
    CodexProvider,
    FallbackAIProvider,
    OpenCodeProvider,
    ProviderBadOutput,
    ProviderEmptyResponse,
    ProviderNotAuthenticated,
    ProviderNotInstalled,
    ProviderRateLimited,
    ProviderTimeout,
    ProviderUnavailable,
    RunResult,
    _run_subprocess,
    build_default_provider,
)
from jarvis_config import JarvisSettings


class RecordingRunner:
    def __init__(self, *results):
        self.results = list(results)
        self.calls = []

    def __call__(self, argv, *, timeout, cwd, env):
        self.calls.append({"argv": argv, "timeout": timeout, "cwd": cwd, "env": dict(env)})
        result = self.results.pop(0) if len(self.results) > 1 else self.results[0]
        if isinstance(result, Exception):
            raise result
        return result


def installed(_binary):
    return "/usr/local/bin/provider"


def missing(_binary):
    return None


class ProviderTests(unittest.TestCase):
    def test_codex_is_invoked_read_only_without_a_shell_or_secrets(self):
        runner = RecordingRunner(RunResult(0, "Hello from Codex\n", ""))
        provider = CodexProvider(runner=runner, which=installed)
        with mock.patch.dict(os.environ, {"JARVIS_MOBILE_TOKEN": "secret", "OPENROUTER_API_KEY": "secret"}):
            reply = provider.ask("hello")

        self.assertEqual(AIReply("Hello from Codex", "codex"), reply)
        call = runner.calls[0]
        self.assertIsInstance(call["argv"], list)
        self.assertIn("exec", call["argv"])
        self.assertIn("--sandbox", call["argv"])
        self.assertIn("read-only", call["argv"])
        self.assertIn("--ephemeral", call["argv"])
        self.assertNotIn("JARVIS_MOBILE_TOKEN", call["env"])
        self.assertNotIn("OPENROUTER_API_KEY", call["env"])
        self.assertTrue(call["cwd"].startswith(tempfile.gettempdir()))
        self.assertEqual(call["cwd"], call["env"]["HOME"])
        self.assertTrue(call["env"]["CODEX_HOME"].startswith(call["cwd"]))
        self.assertIn("--ignore-user-config", call["argv"])
        self.assertNotEqual(os.path.expanduser("~"), call["env"]["HOME"])

    def test_opencode_uses_a_private_data_home(self):
        runner = RecordingRunner(RunResult(0, "Hi", ""))
        provider = OpenCodeProvider(runner=runner, which=installed)
        provider.ask("hello")
        call = runner.calls[0]
        home = call["env"]["HOME"]
        # The home is cached, not the throwaway workdir: the OpenCode wrapper
        # unpacks its runtime into $HOME/.opencode and re-preparing it on every
        # request makes each answer time out.
        self.assertNotEqual(call["cwd"], home)
        self.assertTrue(home.endswith(os.path.join("jarvis-ai", "opencode")), home)
        self.assertNotEqual(os.path.expanduser("~"), home)
        self.assertTrue(call["env"]["XDG_DATA_HOME"].startswith(home))
        self.assertTrue(call["env"]["XDG_CONFIG_HOME"].startswith(home))
        self.assertNotIn("OPENROUTER_API_KEY", call["env"])

    def test_real_output_cap_is_reported_as_bad_output(self):
        script = "import sys; sys.stdout.write('a' * 200000)"
        with tempfile.TemporaryDirectory() as workdir:
            result = _run_subprocess(
                [sys.executable, "-c", script],
                timeout=10,
                cwd=workdir,
                env={"PATH": os.environ.get("PATH", ""), "HOME": workdir},
            )
        self.assertEqual(124, result.returncode)
        self.assertLessEqual(len(result.stdout), 64 * 1024)

    def test_real_subprocess_descendants_are_killed(self):
        script = (
            "import subprocess, sys; "
            "subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)']); "
            "print('done', flush=True)"
        )
        started = time.monotonic()
        with tempfile.TemporaryDirectory() as workdir:
            result = _run_subprocess(
                [sys.executable, "-c", script],
                timeout=10,
                cwd=workdir,
                env={"PATH": os.environ.get("PATH", ""), "HOME": workdir},
            )
        elapsed = time.monotonic() - started
        self.assertEqual(0, result.returncode)
        self.assertIn("done", result.stdout)
        self.assertLess(elapsed, 8, "a leaked descendant held the output pipes open")

    def test_missing_binary_is_reported_as_not_installed(self):
        provider = CodexProvider(runner=RecordingRunner(RunResult(0, "x", "")), which=missing)
        with self.assertRaises(ProviderNotInstalled):
            provider.ask("hello")

    def test_not_logged_in_is_distinct(self):
        provider = CodexProvider(
            runner=RecordingRunner(RunResult(1, "", "Not logged in. Run codex login.")),
            which=installed,
        )
        with self.assertRaises(ProviderNotAuthenticated) as caught:
            provider.ask("hello")
        self.assertIn("not logged in", str(caught.exception))

    def test_timeout_is_distinct(self):
        provider = CodexProvider(
            runner=RecordingRunner(subprocess.TimeoutExpired(["codex"], 60)),
            which=installed,
        )
        with self.assertRaises(ProviderTimeout):
            provider.ask("hello")

    def test_rate_limit_is_distinct(self):
        provider = CodexProvider(
            runner=RecordingRunner(RunResult(1, "", "429 too many requests")),
            which=installed,
        )
        with self.assertRaises(ProviderRateLimited):
            provider.ask("hello")

    def test_empty_output_is_distinct(self):
        provider = CodexProvider(
            runner=RecordingRunner(RunResult(0, "  \n", "")),
            which=installed,
        )
        with self.assertRaises(ProviderEmptyResponse):
            provider.ask("hello")

    def test_unusable_or_oversized_output_is_distinct(self):
        control = CodexProvider(
            runner=RecordingRunner(RunResult(0, "\u0080" * 2000, "")),
            which=installed,
        )
        with self.assertRaises(ProviderBadOutput):
            control.ask("hello")

        oversized = CodexProvider(
            runner=RecordingRunner(RunResult(0, "a" * (70 * 1024), "")),
            which=installed,
        )
        with self.assertRaises(ProviderBadOutput):
            oversized.ask("hello")

    def test_other_failure_does_not_echo_stdout(self):
        provider = CodexProvider(
            runner=RecordingRunner(RunResult(7, "secret user prompt", "crash")),
            which=installed,
        )
        with self.assertRaises(ProviderUnavailable) as caught:
            provider.ask("hello")
        self.assertNotIn("secret user prompt", str(caught.exception))

    def test_opencode_runs_with_tools_denied_and_project_config_disabled(self):
        runner = RecordingRunner(RunResult(0, "Hi from OpenCode", ""))
        provider = OpenCodeProvider(runner=runner, which=installed)
        reply = provider.ask("hello")

        self.assertEqual(AIReply("Hi from OpenCode", "opencode"), reply)
        call = runner.calls[0]
        self.assertIn("run", call["argv"])
        self.assertIn("--pure", call["argv"])
        self.assertIn("jarvis_chat", call["argv"])
        self.assertEqual("1", call["env"]["OPENCODE_DISABLE_PROJECT_CONFIG"])
        content = json.loads(call["env"]["OPENCODE_CONFIG_CONTENT"])
        tools = content["agent"]["jarvis_chat"]["tools"]
        for tool in ("bash", "write", "edit", "task", "todowrite", "webfetch"):
            self.assertFalse(tools[tool], tool)
        # Denying "read" makes OpenCode stall for minutes and answer nothing,
        # so the agent must keep read access and rely on the empty workdir.
        self.assertNotIn("read", tools)
        self.assertNotIn("permission", content["agent"]["jarvis_chat"])

    def test_opencode_model_is_provider_qualified(self):
        provider = OpenCodeProvider(runner=RecordingRunner(RunResult(0, "Hi", "")), which=installed)
        provider.ask("hello")
        argv = provider._argv("hello", "/tmp/workdir")
        self.assertIn("--model", argv)
        model = argv[argv.index("--model") + 1]
        self.assertTrue(model.startswith("opencode/"), model)

    def test_fallback_uses_opencode_when_codex_fails(self):
        codex = CodexProvider(
            runner=RecordingRunner(subprocess.TimeoutExpired(["codex"], 60)),
            which=installed,
        )
        opencode = OpenCodeProvider(
            runner=RecordingRunner(RunResult(0, "Fallback answer", "")),
            which=installed,
        )
        router = FallbackAIProvider([codex, opencode])

        reply = router.ask("hello")

        self.assertEqual("Fallback answer", reply.text)
        self.assertEqual("opencode", reply.provider)

    def test_all_providers_failed_reports_each_distinct_reason(self):
        codex = CodexProvider(
            runner=RecordingRunner(RunResult(0, "x", "")),
            which=missing,
        )
        opencode = OpenCodeProvider(
            runner=RecordingRunner(RunResult(1, "", "Not logged in")),
            which=installed,
        )
        router = FallbackAIProvider([codex, opencode])

        with self.assertRaises(AllProvidersFailed) as caught:
            router.ask("hello")

        kinds = {failure.kind for failure in caught.exception.failures}
        self.assertEqual({"not_installed", "not_authenticated"}, kinds)
        message = str(caught.exception)
        self.assertIn("not installed", message)
        self.assertIn("not logged in", message)

    def test_default_provider_order_can_be_swapped(self):
        settings = JarvisSettings(ai_primary="opencode", ai_fallback="codex")
        router = build_default_provider(settings)
        self.assertEqual(["opencode", "codex"], router.names)

    def test_default_provider_is_codex_then_opencode(self):
        router = build_default_provider(JarvisSettings(ai_primary="codex", ai_fallback="opencode"))
        self.assertEqual(["codex", "opencode"], router.names)

    def test_default_provider_uses_the_warm_server_first(self):
        router = build_default_provider(JarvisSettings())
        self.assertEqual(["opencode-server", "opencode"], router.names)

    def test_server_provider_is_given_a_short_timeout(self):
        router = build_default_provider(JarvisSettings(ai_timeout=120.0))
        server = router.providers[0]
        self.assertLessEqual(server.timeout, 25.0)


if __name__ == "__main__":
    unittest.main()
