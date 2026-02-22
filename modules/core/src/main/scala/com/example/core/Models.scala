package com.example.core

import io.circe.Codec

case class Ping(message: String) derives Codec.AsObject
