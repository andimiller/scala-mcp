package net.andimiller.mcp.examples.rpg

import cats.data.EitherT
import cats.effect.IO
import cats.effect.IOApp
import cats.syntax.all.*

import net.andimiller.enumerive.circe.LabelCodec
import net.andimiller.mcp.core.protocol.ElicitResult
import net.andimiller.mcp.core.protocol.ElicitationError
import net.andimiller.mcp.core.protocol.ToolResult
import net.andimiller.mcp.core.schema.JsonSchema
import net.andimiller.mcp.core.schema.description
import net.andimiller.mcp.core.schema.example
import net.andimiller.mcp.core.server.ElicitationClient
import net.andimiller.mcp.core.server.contextualTool
import net.andimiller.mcp.http4s.McpHttp
import net.andimiller.mcp.http4s.StreamingMcpHttpBuilder

import com.comcast.ip4s.*
import fs2.concurrent.SignallingRef
import io.circe.Codec
import io.circe.Decoder
import io.circe.Encoder
import sttp.apispec.Schema

/** Loosely D&D 5e–themed character creator demonstrating two contrasting paths to the same goal — and the dynamic-tool
  * visibility feature that powers them.
  *
  *   - **Elicitation wizard** (`create_character_wizard`): single tool call; the server pulls four elicitations
  *     from the client in sequence (race → class → weapon → name). Always visible.
  *   - **Step-by-step submit tools**: four tools — `submit_race`, `submit_class`, `submit_weapon`, `submit_name` —
  *     that advance a per-session `CreationPhase` state machine. Exactly one of them is visible at a time, gated by
  *     the current phase. `submit_name` commits the character to history and resets the draft to `Idle`, making
  *     `submit_race` visible again so you can roll another.
  *   - `list_characters` is always available.
  *   - `clear_history` is visible only when the per-session history is non-empty.
  *
  * All dynamic visibility is driven by a `SignallingRef`-backed `.state` slot and `.visibleWhenF` predicates; the
  * framework emits `notifications/tools/list_changed` whenever the visible set changes.
  */
object RpgCharacterCreatorMcpServer extends IOApp.Simple:

  // ── Catalog ────────────────────────────────────────────────────────

  enum Race derives LabelCodec:

    case Dwarf, Elf, Halfling, Human, Dragonborn, Gnome, HalfElf, HalfOrc, Tiefling

  object Race:

    given JsonSchema[Race] with

      def schema: Schema = JsonSchema.string.withEnum(Race.values.toList.map(_.toString)*)

  enum CharClass derives LabelCodec:

    case Barbarian, Bard, Cleric, Druid, Fighter, Monk, Paladin, Ranger, Rogue, Sorcerer, Warlock, Wizard

  object CharClass:

    given JsonSchema[CharClass] with

      def schema: Schema = JsonSchema.string.withEnum(CharClass.values.toList.map(_.toString)*)

    /** Class-flavoured starting-weapon options (loose D&D 5e). */
    def weaponsFor(c: CharClass): List[String] = c match
      case Fighter   => List("longsword", "greatsword", "battleaxe", "warhammer", "rapier")
      case Barbarian => List("greataxe", "greatsword", "handaxe", "warhammer")
      case Wizard    => List("quarterstaff", "dagger", "light crossbow")
      case Sorcerer  => List("dagger", "quarterstaff", "light crossbow")
      case Warlock   => List("dagger", "light crossbow", "scimitar")
      case Cleric    => List("mace", "warhammer", "light crossbow")
      case Druid     => List("quarterstaff", "scimitar", "sling")
      case Paladin   => List("longsword", "warhammer", "javelin")
      case Ranger    => List("longbow", "shortsword", "javelin")
      case Rogue     => List("shortsword", "rapier", "dagger", "shortbow")
      case Monk      => List("shortsword", "quarterstaff", "dart")
      case Bard      => List("rapier", "longsword", "dagger")

  // ── Form types ─────────────────────────────────────────────────────

  case class RaceForm(
      @description("Choose your race")
      race: Race
  ) derives JsonSchema,
        Codec.AsObject

  case class ClassForm(
      @description("Choose your class")
      `class`: CharClass
  ) derives JsonSchema,
        Codec.AsObject

  /** The schema is built dynamically per-class — see `weaponSchemaFor` below. */
  case class WeaponForm(weapon: String) derives Codec.AsObject

  case class NameForm(
      @description("Give your character a name")
      @example("Eldrin Moonshadow")
      name: String
  ) derives JsonSchema,
        Codec.AsObject

  /** Build a `JsonSchema[WeaponForm]` whose `weapon` field's enum is restricted to the given class's starting weapons. */
  def weaponSchemaFor(klass: CharClass): JsonSchema[WeaponForm] =
    new JsonSchema[WeaponForm]:
      def schema: Schema =
        JsonSchema.obj(
          "weapon" -> JsonSchema.string.withEnum(CharClass.weaponsFor(klass)*)
        )

  // ── Tool input/output types ────────────────────────────────────────

  case class CreateCharacterRequest() derives JsonSchema, Decoder

  case class ListCharactersRequest() derives JsonSchema, Decoder

  case class Character(
      @description("The character's name")
      name: String,
      @description("Race")
      race: Race,
      @description("Class")
      `class`: CharClass,
      @description("Starting weapon")
      weapon: String
  ) derives JsonSchema,
        Encoder.AsObject

  case class CharacterList(characters: List[Character]) derives JsonSchema, Encoder.AsObject

  // ── Per-session state ──────────────────────────────────────────────

  /** State machine for the step-by-step submit_* flow. The visibility predicate of each submit tool inspects this to
    * decide whether it should appear in `tools/list`.
    */
  enum CreationPhase:

    case Idle
    case AfterRace(race: Race)
    case AfterClass(race: Race, klass: CharClass)
    case AfterWeapon(race: Race, klass: CharClass, weapon: String)

  /** Plain data — mutation is owned by the framework-provided `SignallingRef` wrapping this value. */
  case class CreatorState(history: List[Character], draft: CreationPhase)

  object CreatorState:

    val empty: CreatorState = CreatorState(Nil, CreationPhase.Idle)

  case class ClearHistoryRequest() derives JsonSchema, Decoder

  case class ClearHistoryResponse(cleared: Int) derives JsonSchema, Encoder.AsObject

  // ── Submit-tool request/response shapes ────────────────────────────

  case class SubmitRaceRequest(
      @description("The race to commit to the current draft character")
      race: Race
  ) derives JsonSchema,
        Decoder

  case class SubmitClassRequest(
      @description("The class to commit to the current draft character")
      `class`: CharClass
  ) derives JsonSchema,
        Decoder

  case class SubmitWeaponRequest(
      @description("Starting weapon — must be one of the options available for the chosen class")
      weapon: String
  ) derives JsonSchema,
        Decoder

  case class SubmitNameRequest(
      @description("Character name — submitting this commits the draft to history")
      @example("Eldrin Moonshadow")
      name: String
  ) derives JsonSchema,
        Decoder

  case class SubmitResponse(
      @description("Human-readable confirmation, including what to do next")
      message: String
  ) derives JsonSchema,
        Encoder.AsObject

  /** The Ctx tuple after `.state` + `.context` declarations below. State is at position 0, elicitation at position 1. */
  type Ctx = (SignallingRef[IO, CreatorState], ElicitationClient[IO])

  // ── Builder configuration ──────────────────────────────────────────

  def configure(builder: StreamingMcpHttpBuilder[IO, Unit]): StreamingMcpHttpBuilder[IO, Ctx] =
    builder
      .context[ElicitationClient[IO]](ctx => IO.pure(ctx.elicitation))
      .state[CreatorState](_ => IO.pure(CreatorState.empty))
      .withContextualTool(
        contextualTool[Ctx]
          .name("create_character_wizard")
          .description(
            "Build a character in a single tool call via elicitation (race → class → weapon → name). " +
              "Requires a client that supports `elicitation/create`. For step-by-step creation without elicitation, " +
              "use the submit_race / submit_class / submit_weapon / submit_name tools as they appear."
          )
          .in[CreateCharacterRequest]
          .out[Character]
          .runResult { case ((state, elic), _) =>
            createWizard(state, elic)
          }
      )
      .withContextualTool(
        contextualTool[Ctx]
          .name("submit_race")
          .description("Start a new draft character by submitting a race. Visible only when no draft is in progress.")
          .in[SubmitRaceRequest]
          .out[SubmitResponse]
          .visibleWhenF { case (state, _) =>
            state.get.map {
              _.draft match
                case CreationPhase.Idle => true
                case _                  => false
            }
          }
          .run { case ((state, _), req) =>
            state.modify { s =>
              (
                s.copy(draft = CreationPhase.AfterRace(req.race)),
                SubmitResponse(s"Race set to ${req.race}. Next: submit_class.")
              )
            }
          }
      )
      .withContextualTool(
        contextualTool[Ctx]
          .name("submit_class")
          .description("Submit a class for the in-progress draft. Visible only after a race has been submitted.")
          .in[SubmitClassRequest]
          .out[SubmitResponse]
          .visibleWhenF { case (state, _) =>
            state.get.map {
              _.draft match
                case _: CreationPhase.AfterRace => true
                case _                          => false
            }
          }
          .runResult { case ((state, _), req) =>
            state.modify { s =>
              s.draft match
                case CreationPhase.AfterRace(race) =>
                  val valid = CharClass.weaponsFor(req.`class`).mkString(", ")
                  (
                    s.copy(draft = CreationPhase.AfterClass(race, req.`class`)),
                    ToolResult.Success(
                      SubmitResponse(
                        s"Class set to ${req.`class`}. Next: submit_weapon. Valid weapons: $valid."
                      )
                    )
                  )
                case other                         =>
                  (s, ToolResult.Error(s"Cannot submit class: draft phase is $other; expected AfterRace."))
            }
          }
      )
      .withContextualTool(
        contextualTool[Ctx]
          .name("submit_weapon")
          .description(
            "Submit a starting weapon for the in-progress draft. Visible only after a class has been submitted. " +
              "Valid choices depend on the class; an invalid weapon returns an error listing the options."
          )
          .in[SubmitWeaponRequest]
          .out[SubmitResponse]
          .visibleWhenF { case (state, _) =>
            state.get.map {
              _.draft match
                case _: CreationPhase.AfterClass => true
                case _                           => false
            }
          }
          .runResult { case ((state, _), req) =>
            state.modify { s =>
              s.draft match
                case CreationPhase.AfterClass(race, klass) =>
                  val valid = CharClass.weaponsFor(klass)
                  if !valid.contains(req.weapon) then
                    (
                      s,
                      ToolResult.Error(
                        s"Invalid weapon '${req.weapon}' for $klass. Valid: ${valid.mkString(", ")}."
                      )
                    )
                  else
                    (
                      s.copy(draft = CreationPhase.AfterWeapon(race, klass, req.weapon)),
                      ToolResult.Success(
                        SubmitResponse(s"Weapon set to ${req.weapon}. Next: submit_name to finalize.")
                      )
                    )
                case other                                 =>
                  (s, ToolResult.Error(s"Cannot submit weapon: draft phase is $other; expected AfterClass."))
            }
          }
      )
      .withContextualTool(
        contextualTool[Ctx]
          .name("submit_name")
          .description(
            "Submit a name to finalize the in-progress draft. Commits the character to history and resets the draft " +
              "so a new one can be started. Visible only after a weapon has been submitted."
          )
          .in[SubmitNameRequest]
          .out[SubmitResponse]
          .visibleWhenF { case (state, _) =>
            state.get.map {
              _.draft match
                case _: CreationPhase.AfterWeapon => true
                case _                            => false
            }
          }
          .runResult { case ((state, _), req) =>
            state.modify { s =>
              s.draft match
                case CreationPhase.AfterWeapon(race, klass, weapon) =>
                  val character = Character(req.name, race, klass, weapon)
                  (
                    s.copy(history = character :: s.history, draft = CreationPhase.Idle),
                    ToolResult.Success(
                      SubmitResponse(
                        s"Character created: ${req.name} the $race $klass with $weapon. Draft cleared; submit_race available again."
                      )
                    )
                  )
                case other                                          =>
                  (s, ToolResult.Error(s"Cannot submit name: draft phase is $other; expected AfterWeapon."))
            }
          }
      )
      .withContextualTool(
        contextualTool[Ctx]
          .name("list_characters")
          .description("List all characters created in this session")
          .in[ListCharactersRequest]
          .out[CharacterList]
          .run { case ((state, _), _) => state.get.map(s => CharacterList(s.history)) }
      )
      .withContextualTool(
        contextualTool[Ctx]
          .name("clear_history")
          .description("Forget every character created in this session")
          .in[ClearHistoryRequest]
          .out[ClearHistoryResponse]
          .visibleWhenF { case (state, _) => state.get.map(_.history.nonEmpty) }
          .run { case ((state, _), _) =>
            state.modify(s => (CreatorState.empty.copy(draft = s.draft), ClearHistoryResponse(s.history.size)))
          }
      )

  /** The wizard itself. Each elicitation step folds the three possible outcomes (accept, decline, cancel) plus errors
    * into an `Either[ToolResult[Character], A]`. We thread those through `EitherT` so the for-comprehension stays flat
    * — short-circuit on the first decline / cancel / error is automatic.
    */
  def createWizard(state: SignallingRef[IO, CreatorState], elic: ElicitationClient[IO]): IO[ToolResult[Character]] =
    val cancelled: ToolResult[Character] = ToolResult.Text("Character creation cancelled")

    type Step[A] = EitherT[IO, ToolResult[Character], A]

    def ask[A](io: IO[Either[ElicitationError, ElicitResult[A]]]): Step[A] =
      EitherT(io.map {
        case Left(ElicitationError.CapabilityMissing) =>
          Left(ToolResult.Error("This client does not support form elicitation"))
        case Left(err)                         => Left(ToolResult.Error(s"Elicitation failed: $err"))
        case Right(ElicitResult.Accept(value)) => Right(value)
        case Right(ElicitResult.Decline)       => Left(cancelled)
        case Right(ElicitResult.Cancel)        => Left(cancelled)
      })

    val flow: Step[Character] =
      for
        race   <- ask(elic.requestForm[RaceForm](message = "Choose your race"))
        klass  <- ask(elic.requestForm[ClassForm](message = "Choose your class"))
        weapon <- {
          given JsonSchema[WeaponForm] = weaponSchemaFor(klass.`class`)
          ask(elic.requestForm[WeaponForm](message = s"Choose a starting weapon for your ${klass.`class`}"))
        }
        name     <- ask(elic.requestForm[NameForm](message = "Give your character a name"))
        character = Character(name = name.name, race = race.race, `class` = klass.`class`, weapon = weapon.weapon)
        _        <- EitherT.liftF[IO, ToolResult[Character], Unit](
                      state.update(s => s.copy(history = character :: s.history))
                    )
      yield character

    flow.fold[ToolResult[Character]](identity, ToolResult.Success(_))

  // ── Server entry point ─────────────────────────────────────────────

  final def run: IO[Unit] =
    configure(
      McpHttp
        .streaming[IO]
        .name("rpg-character-creator-mcp")
        .version("1.0.0")
        .port(port"1974")
        .withExplorer(redirectToRoot = true)
    ).serve.useForever
