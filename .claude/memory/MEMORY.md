Grouped by the moment each memory applies. The trigger is the point of each line, not the topic — read the file when its trigger fires.

**Deciding what to build**
- [Project vision](project-vision.md) — scoping a camera feature: the long-term target list to slice from, never a v1 requirement
- [Minimal infra](feedback-minimal-infra.md) — tempted to add a module, abstraction, or config "for later": don't, ship the smallest working slice
- [GitHub board reference](reference-github-project.md) — creating, labelling, or tracking an issue: board IDs, and the workflow behind /take-issue and /ship

**Writing the code**
- [Camera feasibility on Android](camera-feasibility-android.md) — touching capture, sensor control, or the image pipeline: which Android API can actually do it and what it demands of the device
- [Design direction](project-design-direction.md) — changing how anything looks: flat "minimal chrome" is the shipped identity, and these directions are already ruled out

**Finishing a change**
- [Verification](feedback-verification.md) — code is written: run tests + installDebug, nothing beyond that, and keep the Gradle log out of context

**Running the session**
- [Session economy](feedback-session-economy.md) — starting a task, weighing a subagent, or a session that has run long: keep it short and shallow, context length dominates cost
