package io.janstenpickle.controller.sonos

case class NowPlaying(
  title: String,
  artist: String,
  album: Option[String],
  albumArtist: Option[String],
  artwork: Option[String]
) {
  lazy val toMap: Map[String, String] =
    Map("title" -> title, "artist" -> artist) ++ album.map("album" -> _) ++ albumArtist.map("albumArtist" -> _) ++ artwork
      .map("artwork" -> _)
}
