package net.andimiller.mcp.examples.rpg

import cats.data.EitherT
import cats.effect.{IO, IOApp, Ref}
import cats.syntax.all.*
import com.comcast.ip4s.*
import io.circe.{Codec, Decoder, Encoder}
import io.circe.syntax.*
import net.andimiller.enumerive.circe.LabelCodec
import net.andimiller.mcp.core.protocol.{ElicitResult, ElicitationError, ToolResult}
import net.andimiller.mcp.core.schema.{JsonSchema, description, example}
import net.andimiller.mcp.core.server.{ElicitationClient, McpDsl}
import net.andimiller.mcp.http4s.{McpHttp, StreamingMcpHttpBuilder}
import sttp.apispec.Schema

/** Loosely D&D 5e–themed character creator demonstrating multi-step elicitation over HTTP.
 *
 * The wizard runs entirely inside one `create_character` tool invocation:
 *   1. Pick a race (9 options).
 *   2. Pick a class (12 options).
 *   3. Pick a starting weapon — the enum is generated dynamically from the chosen class.
 *   4. Name the character.
 *
 * A separate `list_characters` tool reads the per-session history.
 */
object RpgCharacterCreatorMcpServer extends IOApp.Simple, McpDsl[IO]:

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
  ) derives JsonSchema, Codec.AsObject

  case class ClassForm(
    @description("Choose your class")
    `class`: CharClass
  ) derives JsonSchema, Codec.AsObject

  /** The schema is built dynamically per-class — see `weaponSchemaFor` below. */
  case class WeaponForm(weapon: String) derives Codec.AsObject

  case class NameForm(
    @description("Give your character a name")
    @example("Eldrin Moonshadow")
    name: String
  ) derives JsonSchema, Codec.AsObject

  /** Build a `JsonSchema[WeaponForm]` whose `weapon` field's enum is restricted to the
   *  given class's starting weapons. */
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
  ) derives JsonSchema, Encoder.AsObject

  case class CharacterList(characters: List[Character]) derives JsonSchema, Encoder.AsObject

  // ── Per-session state ──────────────────────────────────────────────

  case class CreatorState(
    history: Ref[IO, List[Character]],
    elicitation: ElicitationClient[IO]
  )

  object CreatorState:
    def create(elicitation: ElicitationClient[IO]): IO[CreatorState] =
      Ref.of[IO, List[Character]](Nil).map(CreatorState(_, elicitation))

  // ── Builder configuration ──────────────────────────────────────────

  def configure(builder: StreamingMcpHttpBuilder[IO, Unit]): StreamingMcpHttpBuilder[IO, CreatorState] =
    builder
      .stateful[CreatorState](ctx => CreatorState.create(ctx.elicitation))
      .withContextualTool(
        contextualTool[CreatorState].name("create_character")
          .description("Build a D&D-flavoured character interactively (race → class → weapon → name)")
          .in[CreateCharacterRequest]
          .runResult[Character] { (state, _) =>
            createWizard(state)
          }
      )
      .withContextualTool(
        contextualTool[CreatorState].name("list_characters")
          .description("List all characters created in this session")
          .in[ListCharactersRequest]
          .run((state, _) => state.history.get.map(CharacterList(_)))
      )

  /** The wizard itself. Each elicitation step folds the three possible outcomes (accept,
   *  decline, cancel) plus errors into an `Either[ToolResult[Character], A]`. We thread
   *  those through `EitherT` so the for-comprehension stays flat — short-circuit on the
   *  first decline / cancel / error is automatic. */
  def createWizard(state: CreatorState): IO[ToolResult[Character]] =
    val elic = state.elicitation
    val cancelled: ToolResult[Character] = ToolResult.Text("Character creation cancelled")

    type Step[A] = EitherT[IO, ToolResult[Character], A]

    def ask[A](io: IO[Either[ElicitationError, ElicitResult[A]]]): Step[A] =
      EitherT(io.map {
        case Left(ElicitationError.CapabilityMissing) =>
          Left(ToolResult.Error("This client does not support form elicitation"))
        case Left(err)                                => Left(ToolResult.Error(s"Elicitation failed: $err"))
        case Right(ElicitResult.Accept(value))        => Right(value)
        case Right(ElicitResult.Decline)              => Left(cancelled)
        case Right(ElicitResult.Cancel)               => Left(cancelled)
      })

    val flow: Step[Character] =
      for
        race  <- ask(elic.requestForm[RaceForm](message  = "Choose your race"))
        klass <- ask(elic.requestForm[ClassForm](message = "Choose your class"))
        weapon <- {
          given JsonSchema[WeaponForm] = weaponSchemaFor(klass.`class`)
          ask(elic.requestForm[WeaponForm](message = s"Choose a starting weapon for your ${klass.`class`}"))
        }
        name      <- ask(elic.requestForm[NameForm](message = "Give your character a name"))
        character  = Character(name = name.name, race = race.race, `class` = klass.`class`, weapon = weapon.weapon)
        _         <- EitherT.liftF[IO, ToolResult[Character], Unit](state.history.update(character :: _))
      yield character

    flow.fold[ToolResult[Character]](identity, ToolResult.Success(_))

  // ── Server entry point ─────────────────────────────────────────────

  final def run: IO[Unit] =
    configure(
      McpHttp.streaming[IO]
        .name("rpg-character-creator-mcp")
        .version("1.0.0")
        .port(port"1974")
        .withExplorer(redirectToRoot = true)
    ).serve.useForever
