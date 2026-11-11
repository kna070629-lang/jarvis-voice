import unittest

from jarvis_core import JarvisCore, Message, Status
from jarvis_core.context import ContextStore
from jarvis_core.engines.comms import CommunicationEngine
from jarvis_core.engines.memory import MemoryEngine
from jarvis_core.engines.model_router import ModelRouter
from jarvis_core.engines.planner import PlannerEngine
from jarvis_core.engines.security import SecurityEngine
from jarvis_core.engines.verification import VerificationEngine
from jarvis_core.events import ConnectionTracker, EventBus
from jarvis_core.graph import Task, TaskGraph
from jarvis_core.topology import all_links, all_nodes, node_positions


class FakeProvider:
    """A provider that answers without touching the network."""

    name = "fake"

    def __init__(self, text: str = "A real answer.", fail: bool = False):
        self.text = text
        self.fail = fail
        self.model = "fake-model"
        self.calls = 0

    def installed(self) -> bool:
        return True

    def ask(self, prompt, context=""):
        self.calls += 1
        if self.fail:
            raise RuntimeError("provider is down")
        from ai_provider import AIReply
        return AIReply(self.text, self.name)


class CoreTests(unittest.TestCase):
    def setUp(self):
        self.provider = FakeProvider()
        self.core = JarvisCore(providers=[self.provider], repository_root=".")

    # -- topology ---------------------------------------------------------
    def test_the_network_has_the_three_levels(self):
        nodes = all_nodes()
        kinds = [node["kind"] for node in nodes]
        self.assertEqual(1, kinds.count("core"))
        self.assertEqual(6, kinds.count("engine"))
        self.assertEqual(7, kinds.count("cluster"))
        self.assertEqual(14, len(nodes))

    def test_the_core_is_in_the_middle(self):
        positions = node_positions()
        self.assertAlmostEqual(0.5, positions["core"][0])
        self.assertAlmostEqual(0.5, positions["core"][1])

    def test_the_engines_form_a_ring_around_the_core(self):
        positions = node_positions()
        import math
        for node_id in ("engine.memory", "engine.planner", "engine.model_router",
                        "engine.security", "engine.verification", "engine.comms"):
            x, y = positions[node_id]
            distance = math.hypot(x - 0.5, y - 0.5)
            self.assertGreater(distance, 0.1)
            self.assertLess(distance, 0.3)

    def test_the_clusters_sit_outside_the_engines(self):
        positions = node_positions()
        import math
        inner = max(
            math.hypot(x - 0.5, y - 0.5)
            for node_id, (x, y) in positions.items()
            if node_id.startswith("engine.")
        )
        for node_id, (x, y) in positions.items():
            if node_id.startswith("cluster."):
                self.assertGreater(math.hypot(x - 0.5, y - 0.5), inner)

    def test_the_inner_ring_has_crossing_connections(self):
        links = all_links()
        inner = [link for link in links if link[0].startswith("engine.") and link[1].startswith("engine.")]
        # Each engine is joined to the two engines two steps away and the one
        # opposite it, which is what makes the connections cross.
        self.assertEqual(12, len(inner))
        crossings = [link for link in inner if link[1].endswith("comms") or link[1].endswith("planner")]
        self.assertGreater(len(crossings), 0)

    def test_every_cluster_reaches_an_engine(self):
        links = all_links()
        for node_id, _ in [(node["node_id"], node) for node in all_nodes() if node["kind"] == "cluster"]:
            connected = [link for link in links if link[0] == node_id or link[1] == node_id]
            self.assertTrue(connected, node_id)

    # -- registration -----------------------------------------------------
    def test_every_agent_is_registered(self):
        agents = self.core.registry.all()
        self.assertEqual(13, len(agents))
        ids = {agent.id for agent in agents}
        for expected in ("engine.memory", "engine.planner", "engine.model_router",
                         "engine.security", "engine.verification", "engine.comms",
                         "cluster.voice", "cluster.research", "cluster.development",
                         "cluster.device", "cluster.automation", "cluster.knowledge",
                         "cluster.qa"):
            self.assertIn(expected, ids)
        # The central core is the thirteenth node and is not an agent.
        self.assertNotIn("core", ids)
        self.assertIn("core", {node["node_id"] for node in all_nodes()})

    def test_the_named_specialist_roles_are_assigned(self):
        roles = {agent.id: getattr(agent, "role", "") for agent in self.core.registry.all()}
        self.assertEqual("LUNA", roles["cluster.voice"])
        self.assertEqual("VEGA", roles["cluster.research"])
        self.assertEqual("ORION", roles["cluster.development"])
        self.assertEqual("TITAN", roles["cluster.device"])
        self.assertEqual("ATLAS", roles["cluster.automation"])
        self.assertEqual("NOVA", roles["cluster.qa"])

    def test_agents_declare_their_capabilities(self):
        for agent in self.core.registry.all():
            self.assertTrue(agent.id)
            self.assertTrue(agent.name)

    # -- orchestration ----------------------------------------------------
    def test_a_request_produces_a_trace(self):
        outcome = self.core.execute("what time is it")
        self.assertTrue(outcome["trace"])
        self.assertEqual("time", outcome["trace"][0]["task_type"])
        self.assertIn("It is", outcome["answer"])

    def test_a_device_action_is_produced_for_an_app_launch(self):
        outcome = self.core.execute("open youtube")
        action = outcome["action_request"]
        self.assertIsNotNone(action)
        self.assertEqual("open_app", action["action"])
        self.assertEqual("youtube", action["args"]["name"])

    def test_a_sensitive_action_asks_for_confirmation(self):
        outcome = self.core.execute("call 919876543210")
        action = outcome["action_request"]
        self.assertTrue(action["requires_confirmation"])
        self.assertIn("Shall I call", outcome["answer"])

    def test_a_call_by_name_becomes_a_contact_action(self):
        outcome = self.core.execute("call ramesh")
        action = outcome["action_request"]
        self.assertEqual("call_contact", action["action"])

    def test_the_answer_comes_from_the_model_when_the_model_is_needed(self):
        outcome = self.core.execute("who is Nikola Tesla")
        self.assertIn("A real answer", outcome["answer"])
        self.assertGreater(self.provider.calls, 0)

    def test_the_model_falls_back_to_the_next_provider(self):
        broken = FakeProvider(fail=True)
        working = FakeProvider(text="Recovered answer.")
        core = JarvisCore(providers=[broken, working])
        outcome = core.execute("who is Nikola Tesla")
        self.assertIn("Recovered answer", outcome["answer"])

    def test_the_context_is_shared_between_tasks(self):
        store = ContextStore()
        reference = store.create({"seed": 1})
        store.merge(reference, {"found": "value"})
        self.assertEqual("value", store.get(reference)["found"])

    # -- security ---------------------------------------------------------
    def test_a_forbidden_action_is_refused(self):
        decision = self.core.security.check("purchase", [], {})
        self.assertFalse(decision.allowed)

    def test_a_shell_command_in_a_payload_is_refused(self):
        decision = self.core.security.check("open_app", [], {"name": "x; rm -rf /"})
        self.assertFalse(decision.allowed)

    def test_a_missing_permission_is_refused(self):
        decision = self.core.security.check("call", ["android.permission.CALL_PHONE"], {})
        self.assertFalse(decision.allowed)
        self.assertIn("android.permission.CALL_PHONE", decision.missing_permissions)

    def test_a_granted_permission_passes(self):
        self.core.security.set_granted_permissions({"android.permission.CALL_PHONE"})
        decision = self.core.security.check("call", ["android.permission.CALL_PHONE"], {})
        self.assertTrue(decision.allowed)

    def test_security_decisions_are_logged(self):
        self.core.security.check("call", [], {})
        self.assertTrue(self.core.security.recent_decisions())

    # -- verification -----------------------------------------------------
    def test_an_action_claim_without_evidence_is_caught(self):
        result = self.core.verification.verify("I have sent the message.", [], "message")
        self.assertFalse(result.ok)

    def test_an_action_claim_with_evidence_passes(self):
        result = self.core.verification.verify(
            "I have sent the message.", [{"kind": "evidence"}], "message")
        self.assertTrue(result.ok)

    def test_an_empty_answer_fails(self):
        result = self.core.verification.verify("", [], "answer")
        self.assertFalse(result.ok)

    def test_a_conflicting_answer_fails(self):
        result = self.core.verification.verify(
            "I cannot do that, it was completed successfully.", [], "answer")
        self.assertFalse(result.ok)

    # -- memory -----------------------------------------------------------
    def test_the_memory_engine_saves_and_retrieves(self):
        memory = MemoryEngine()
        memory.remember("The user likes cricket")
        self.assertTrue(memory.retrieve("cricket"))
        self.assertEqual(1, len(memory.facts()))

    def test_the_memory_engine_keeps_the_conversation(self):
        memory = MemoryEngine()
        memory.record("user", "hello")
        memory.record("assistant", "hi there")
        self.assertEqual(2, len(memory.history()))

    # -- model router -----------------------------------------------------
    def test_the_router_reports_which_provider_answered(self):
        router = ModelRouter([FakeProvider(text="Router answer")])
        answer = router.complete("a question")
        self.assertEqual("Router answer", answer["text"])
        self.assertEqual("fake", answer["provider"])

    def test_the_router_falls_back(self):
        router = ModelRouter([FakeProvider(fail=True), FakeProvider(text="Second")])
        answer = router.complete("a question")
        self.assertEqual("Second", answer["text"])
        self.assertEqual(1, len(answer["failures"]))

    def test_the_router_reports_no_answer_when_all_fail(self):
        router = ModelRouter([FakeProvider(fail=True)])
        answer = router.complete("a question")
        self.assertEqual("", answer["text"])
        self.assertTrue(answer["failures"])

    # -- planning ---------------------------------------------------------
    def test_the_planner_makes_one_task_for_a_simple_request(self):
        plan = PlannerEngine().plan("open youtube")
        self.assertEqual(1, len(plan.graph.all()))
        self.assertEqual("launch", plan.graph.all()[0].task_type)

    def test_the_planner_adds_a_context_task_for_a_research_request(self):
        plan = PlannerEngine().plan("research the history of the eiffel tower")
        types = [task.task_type for task in plan.graph.all()]
        self.assertIn("gather_context", types)
        self.assertIn("research", types)

    def test_a_task_graph_reports_a_dependency_cycle(self):
        graph = TaskGraph()
        graph.add(Task("a", "x", "a", depends_on=["b"]))
        graph.add(Task("b", "x", "b", depends_on=["a"]))
        self.assertTrue(graph.detect_cycles())

    # -- communication ----------------------------------------------------
    def test_a_message_to_an_unknown_route_is_dropped(self):
        comms = CommunicationEngine()
        message = Message(task_id="t", agent_id="a", source="a", destination="nowhere",
                           task_type="x")
        self.assertIsNone(comms.route(message))
        self.assertEqual(1, comms.stats()["dropped"])

    def test_a_registered_route_carries_the_message(self):
        comms = CommunicationEngine()
        def handler(message):
            reply = message.copy()
            reply.status = Status.COMPLETED
            return reply
        comms.register_route("core", handler)
        message = Message(task_id="t", agent_id="a", source="a", destination="core",
                           task_type="x")
        reply = comms.route(message)
        self.assertIsNotNone(reply)
        self.assertEqual(1, comms.stats()["received"])

    def test_the_connection_tracker_counts_transmissions(self):
        tracker = ConnectionTracker()
        tracker.declare("a", "b")
        message = Message(task_id="t", agent_id="a", source="a", destination="b", task_type="x")
        tracker.set_status("a", "b", "transmitting", message)
        tracker.settle("a", "b", True)
        link = tracker.links()[0]
        self.assertEqual(1, link.transmissions)
        self.assertEqual("successful", link.status)

    # -- live view --------------------------------------------------------
    def test_the_network_view_reports_every_node_and_link(self):
        view = self.core.topology()
        # The architecture nodes, the core, the engines and the clusters, plus
        # one node for every external service.
        kinds = [node["kind"] for node in view["nodes"]]
        self.assertEqual(1, kinds.count("core"))
        self.assertEqual(6, kinds.count("engine"))
        self.assertEqual(7, kinds.count("cluster"))
        self.assertGreaterEqual(kinds.count("service"), 8)
        self.assertEqual(32, len(view["links"]))
        for node in view["nodes"]:
            self.assertIn("status", node)
            self.assertIn("position", node)

    def test_the_service_states_are_real_and_not_guessed(self):
        summary = self.core.services.summary()
        self.assertGreaterEqual(summary["services"], 8)
        # The local services are checked for real.
        self.core.services.check("memory.store")
        self.core.services.check("tools.local")
        states = {report.service_id: report.state for report in self.core.services.reports()}
        # The memory and the local tools work without any credential.
        self.assertEqual("connected", states["memory.store"])
        self.assertEqual("connected", states["tools.local"])
        # A service with no credential must never claim to be connected.
        for report in self.core.services.reports():
            if report.service_id in ("ai.openrouter", "ai.openai"):
                self.assertNotEqual("connected", report.state)

    def test_the_node_detail_reports_the_links_of_a_node(self):
        detail = self.core.node_detail("engine.memory")
        self.assertTrue(detail["found"])
        self.assertTrue(detail["links"])

    def test_activity_is_recorded_for_the_view(self):
        self.core.note_activity("model", 120, "fake")
        view = self.core.topology()
        core_node = [node for node in view["nodes"] if node["node_id"] == "core"][0]
        self.assertIn("model", core_node["detail"])


if __name__ == "__main__":
    unittest.main()
