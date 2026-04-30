# RPG Character Creator

`modules/example-rpg-character-creator` · HTTP + SSE · JVM · port 1974

A JVM HTTP server demonstrating **multi-step elicitation over HTTP**. The
`create_character` tool walks the client through race → class → starting weapon
→ name using `requestForm` calls, where the weapon enum is generated
dynamically from the chosen class.

- **Tools:** `create_character` (interactive wizard), `list_characters`
- **Per-session state:** each session keeps its own list of created characters
- A good example of folding `ElicitResult.{Accept, Decline, Cancel}` and `ElicitationError` into a clean `EitherT` flow

## Build and run

```bash
sbt exampleRpgCharacterCreator/run
# Server starts on http://0.0.0.0:1974
```
