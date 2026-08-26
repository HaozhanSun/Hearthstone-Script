# End-to-end exit criterion

Unless the user explicitly overrides it, every implementation change in this
session has the following completion gate:

1. The current deployed build completes two consecutive real Hearthstone games
   from start to a terminal screen without agent intervention.
2. The script process remains alive through both games, with no unhandled crash
   or unexplained self-exit in the run log.
3. Each game has a fresh authoritative terminal marker in the current
   `Power.log`. For a successful-win claim, the marker must be
   `PLAYSTATE=WON`.
4. A screenshot saved by the run at the end of the second game is retained as
   evidence, together with the exact log path and run identifier.

A single game, a simulated test, an old screenshot, or a run that required
manual recovery does not satisfy this criterion. The final report must link to
the saved evidence and state which build produced it.
