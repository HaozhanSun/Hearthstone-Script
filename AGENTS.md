# Repository guidance

## Modular implementation and regression boundaries

- Prefer well-isolated, modular, composable changes that can scale without spreading policy across unrelated subsystems. Give each component clear ownership and stable interfaces, with clean plug/unplug boundaries between policy, orchestration, dispatch, and persistence.
- Avoid one-off hacks, special-case branches, and cross-cutting coupling when a reusable policy or adapter boundary fits. Keep behavior changes scoped to the smallest responsible component and preserve unrelated behavior.
- Every behavior change must include focused regression tests at the changed boundary and an integration-level check for its consumers. Tests should verify observable outcomes and rejection/failure behavior, not merely that an action was logged or requested.
- When a request contains multiple verifications, track each item separately; a blocker pauses only dependent checks, while safe independent checks continue sequentially, and the whole task is not complete until every required item passes.
