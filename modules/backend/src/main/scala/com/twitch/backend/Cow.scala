package com.twitch.backend

import scala.annotation.tailrec

// A minimal interpreter for a subset of the COW esolang (moo/mOo/moO/MoO/MOo/MOO/moo/OOO/OOM),
// just enough to compute an Int the roundabout way. Not the Moo4S library (unpublished, Scala 3
// only via source) — this is our own tiny VM in the same spirit.
object Cow {

  private case class State(ip: Int, ptr: Int, tape: Map[Int, Int], output: Int)

  def run(program: String): Int = {
    val tokens = program.split("\\s+").filter(_.nonEmpty)
    val loopMatches = matchLoops(tokens)

    @tailrec
    def loop(s: State): State =
      if s.ip >= tokens.length then s
      else {
        val cell = s.tape.getOrElse(s.ptr, 0)
        tokens(s.ip) match {
          case "MoO" => loop(s.copy(ip = s.ip + 1, tape = s.tape.updated(s.ptr, cell + 1)))
          case "MOo" => loop(s.copy(ip = s.ip + 1, tape = s.tape.updated(s.ptr, cell - 1)))
          case "mOo" => loop(s.copy(ip = s.ip + 1, ptr = s.ptr - 1))
          case "moO" => loop(s.copy(ip = s.ip + 1, ptr = s.ptr + 1))
          case "OOO" => loop(s.copy(ip = s.ip + 1, tape = s.tape.updated(s.ptr, 0)))
          case "OOM" => loop(s.copy(ip = s.ip + 1, output = cell))
          case "MOO" =>
            if cell == 0 then loop(s.copy(ip = loopMatches(s.ip) + 1))
            else loop(s.copy(ip = s.ip + 1))
          case "moo" => loop(s.copy(ip = loopMatches(s.ip)))
          case other => throw new IllegalArgumentException(s"Unsupported COW instruction: $other")
        }
      }

    loop(State(ip = 0, ptr = 0, tape = Map.empty, output = 0)).output
  }

  private def matchLoops(tokens: Array[String]): Map[Int, Int] = {
    @tailrec
    def go(i: Int, openStack: List[Int], acc: Map[Int, Int]): Map[Int, Int] =
      if i >= tokens.length then acc
      else
        tokens(i) match {
          case "MOO" => go(i + 1, i :: openStack, acc)
          case "moo" =>
            openStack match {
              case open :: rest => go(i + 1, rest, acc.updated(open, i).updated(i, open))
              case Nil => throw new IllegalArgumentException(s"Unmatched moo at token $i")
            }
          case _ => go(i + 1, openStack, acc)
        }

    go(0, Nil, Map.empty)
  }

}
