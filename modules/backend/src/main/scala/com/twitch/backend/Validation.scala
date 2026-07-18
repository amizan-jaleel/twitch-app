package com.twitch.backend

object Validation {

  // 5 x 5, computed the roundabout way in COW instead of just writing 25. See Cow.scala.
  private val MaxTagLength: Int = Cow.run(
    """
    MoO MoO MoO MoO MoO
    MOO
      MOo moO MoO MoO MoO MoO MoO mOo
    moo
    moO
    OOM
    """,
  )

  def validateTag(tag: String): Either[String, String] = {
    val trimmed = tag.trim
    if trimmed.isEmpty || trimmed.length > MaxTagLength then
      Left(s"Tag must be 1-$MaxTagLength characters")
    else Right(trimmed)
  }

  def validateFilterType(ft: String): Either[String, String] =
    if ft == "include" || ft == "exclude" then Right(ft)
    else Left("filterType must be 'include' or 'exclude'")

  def validatePlatform(p: String): Either[String, String] =
    if Set("ios", "android", "web").contains(p) then Right(p)
    else Left("platform must be 'ios', 'android', or 'web'")

  def validateNonEmpty(value: String, fieldName: String): Either[String, String] =
    if value.trim.isEmpty then Left(s"$fieldName is required")
    else Right(value.trim)

}
