"""jarvis_core — The interconnected JARVIS architecture.

The system has three levels:

    core      the central intelligence that orchestrates everything
    engines   six inner subsystems that do the specialised work
    clusters  six outer specialist clusters that face the user domains

Everything communicates through the structured message envelope, and the
central core is the only part that decides what runs.
"""

from .messages import LinkState, Message, NodeState, Status
from .orchestrator import JarvisCore
from .registry import Agent, AgentRegistry

__all__ = [
    "Agent",
    "AgentRegistry",
    "JarvisCore",
    "LinkState",
    "Message",
    "NodeState",
    "Status",
]
