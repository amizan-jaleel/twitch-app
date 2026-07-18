package com.twitch.backend

import munit.FunSuite

class CowSpec extends FunSuite {

  test("run: increments then prints") {
    assertEquals(Cow.run("MoO MoO MoO OOM"), 3)
  }

  test("run: loop computes 5 x 5") {
    val program =
      """
      MoO MoO MoO MoO MoO
      MOO
        MOo moO MoO MoO MoO MoO MoO mOo
      moo
      moO
      OOM
      """
    assertEquals(Cow.run(program), 25)
  }

  test("run: zero cell never enters the loop") {
    val program =
      """
      MOO
        MoO MoO MoO
      moo
      OOM
      """
    assertEquals(Cow.run(program), 0)
  }

  test("run: unsupported token throws") {
    intercept[IllegalArgumentException](Cow.run("mOO"))
  }

}
