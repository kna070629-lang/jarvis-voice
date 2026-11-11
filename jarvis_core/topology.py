"""jarvis_core.topology — The shape of the network.

This defines the geometry of the architecture and the connections between the
nodes. The backend uses it for routing and the interface uses it to draw the
network, so the picture is always the real thing and never a decoration.

The shape has three levels:

    the central core
    a compact inner ring of six engines with crossing connections
    six satellite clusters arranged radially around that ring
"""

from __future__ import annotations

import math


# The central node.
CORE_ID = "core"
CORE_NAME = "JARVIS Core"

# The six inner engines, in the order they appear around the ring.
INNER_ENGINES = [
    ("engine.memory", "Memory Engine"),
    ("engine.planner", "Task Planning Engine"),
    ("engine.model_router", "Model Router"),
    ("engine.security", "Security Engine"),
    ("engine.verification", "Verification Engine"),
    ("engine.comms", "Context and Communication Engine"),
]

# The six outer clusters.
OUTER_CLUSTERS = [
    ("cluster.voice", "Voice and Conversation"),
    ("cluster.research", "Research and Web"),
    ("cluster.development", "Development and Coding"),
    ("cluster.device", "Android and Device Control"),
    ("cluster.automation", "Automation and Tools"),
    ("cluster.knowledge", "Knowledge and Learning"),
]


def _ring(count: int, radius: float, start_degrees: float = -90.0) -> list[tuple[float, float]]:
    """Evenly spaced points on a circle, as fractions of the canvas."""
    points = []
    for index in range(count):
        angle = math.radians(start_degrees + index * (360.0 / count))
        points.append((
            0.5 + math.cos(angle) * radius,
            0.5 + math.sin(angle) * radius,
        ))
    return points


# The positions of every node.
def node_positions() -> dict[str, tuple[float, float]]:
    """Every node with its position as a fraction of the canvas."""
    positions: dict[str, tuple[float, float]] = {CORE_ID: (0.5, 0.5)}
    for (node_id, _), (x, y) in zip(INNER_ENGINES, _ring(6, 0.215, -90.0)):
        positions[node_id] = (x, y)
    # The satellites sit on the same spokes but further out, turned slightly so
    # the ring and the satellites do not line up into spokes.
    for (node_id, _), (x, y) in zip(OUTER_CLUSTERS, _ring(6, 0.435, -60.0)):
        positions[node_id] = (x, y)
    return positions


def inner_links() -> list[tuple[str, str]]:
    """The connections inside the inner ring.

    Every engine is joined to the two engines that are two steps away, which
    produces the crossing connections, and each engine is joined to the one
    opposite it.
    """
    ids = [node_id for node_id, _ in INNER_ENGINES]
    links: list[tuple[str, str]] = []
    for index, node_id in enumerate(ids):
        links.append((node_id, ids[(index + 2) % len(ids)]))
        links.append((node_id, ids[(index + 3) % len(ids)]))
    return links


def core_links() -> list[tuple[str, str]]:
    """The central node joined to every engine."""
    return [(CORE_ID, node_id) for node_id, _ in INNER_ENGINES]


def satellite_links() -> list[tuple[str, str]]:
    """Each satellite joined to the engine that owns it and one more.

    The second connection crosses the inner ring, so a satellite is reachable
    both directly and indirectly.
    """
    engines = [node_id for node_id, _ in INNER_ENGINES]
    clusters = [node_id for node_id, _ in OUTER_CLUSTERS]
    links: list[tuple[str, str]] = []
    for index, cluster in enumerate(clusters):
        links.append((cluster, engines[index % len(engines)]))
        links.append((cluster, engines[(index + 2) % len(engines)]))
    return links


def all_links() -> list[tuple[str, str]]:
    """Every connection in the network."""
    return core_links() + inner_links() + satellite_links()


def all_nodes() -> list[dict]:
    """Every node with its identity, kind and position."""
    positions = node_positions()
    nodes: list[dict] = [{
        "node_id": CORE_ID,
        "kind": "core",
        "name": CORE_NAME,
        "cluster": "",
        "position": list(positions[CORE_ID]),
    }]
    for node_id, name in INNER_ENGINES:
        nodes.append({
            "node_id": node_id,
            "kind": "engine",
            "name": name,
            "cluster": "",
            "position": list(positions[node_id]),
        })
    for node_id, name in OUTER_CLUSTERS:
        nodes.append({
            "node_id": node_id,
            "kind": "cluster",
            "name": name,
            "cluster": node_id,
            "position": list(positions[node_id]),
        })
    return nodes


def node_name(node_id: str) -> str:
    if node_id == CORE_ID:
        return CORE_NAME
    for candidate, name in INNER_ENGINES + OUTER_CLUSTERS:
        if candidate == node_id:
            return name
    return node_id


def cluster_for_node(node_id: str) -> str:
    """The engine or cluster a node belongs to, used for grouping."""
    for candidate, _ in INNER_ENGINES:
        if candidate == node_id:
            return "inner"
    for candidate, _ in OUTER_CLUSTERS:
        if candidate == node_id:
            return "outer"
    return "core"
