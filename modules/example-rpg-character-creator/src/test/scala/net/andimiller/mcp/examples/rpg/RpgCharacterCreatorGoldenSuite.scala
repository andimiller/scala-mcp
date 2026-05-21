package net.andimiller.mcp.examples.rpg

import cats.effect.IO

import net.andimiller.mcp.core.server.Server
import net.andimiller.mcp.golden.McpGoldenSuite
import net.andimiller.mcp.http4s.McpHttp

import RpgCharacterCreatorMcpServer.*

private val sampleCharacter = Character(
  name = "Thorin",
  race = Race.Dwarf,
  `class` = CharClass.Fighter,
  weapon = "warhammer"
)

/** Idle initial state. Visible: `create_character_wizard`, `list_characters`, `submit_race`. */
class RpgCharacterCreatorGoldenSuite extends McpGoldenSuite:

  override def goldenFileName = "rpg-character-creator-mcp.json"

  def server: IO[Server[IO]] =
    RpgCharacterCreatorMcpServer
      .configure(McpHttp.streaming[IO].name("rpg-character-creator-mcp").version("1.0.0"))
      .buildServer

/** Idle draft with a populated history. Adds `clear_history` to the visible set on top of the idle defaults. */
class RpgCharacterCreatorPopulatedGoldenSuite extends McpGoldenSuite:

  override def goldenFileName = "rpg-character-creator-mcp-populated.json"

  def server: IO[Server[IO]] =
    RpgCharacterCreatorMcpServer
      .configure(McpHttp.streaming[IO].name("rpg-character-creator-mcp").version("1.0.0"))
      .buildServerWith { case (state, _) =>
        state.set(CreatorState(List(sampleCharacter), CreationPhase.Idle))
      }

/** Mid-draft snapshot: race submitted, awaiting class. The visible submit_* tool shifts from `submit_race` to
  * `submit_class` — the dynamic-visibility transition this example is meant to demonstrate.
  */
class RpgCharacterCreatorMidDraftGoldenSuite extends McpGoldenSuite:

  override def goldenFileName = "rpg-character-creator-mcp-mid-draft.json"

  def server: IO[Server[IO]] =
    RpgCharacterCreatorMcpServer
      .configure(McpHttp.streaming[IO].name("rpg-character-creator-mcp").version("1.0.0"))
      .buildServerWith { case (state, _) =>
        state.set(CreatorState(Nil, CreationPhase.AfterRace(Race.Elf)))
      }
