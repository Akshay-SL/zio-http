package zio.http

import zio.Console.printLine
import zio._
import zio.test.Assertion._
import zio.test._

import zio.http.ChannelEvent.{Read, Unregistered, UserEvent, UserEventTriggered}
import zio.http.netty.server.NettyDriver
import zio.http.{Headers, Status, Version}

object SocketContractSpec extends ZIOSpecDefault {

  def spec: Spec[Scope, Any] =
    suite("SocketOps")(
      contract("Successful Multi-message application") { p =>
        val socketServer: WebSocketApp[Any] =
          Handler.webSocket { channel =>
            channel.receiveAll {
              case Read(WebSocketFrame.Text("Hi Server"))          =>
                printLine("Server got message: Hi Server") *> channel.shutdown
              case UserEventTriggered(UserEvent.HandshakeComplete) =>
                channel.send(ChannelEvent.Read(WebSocketFrame.text("Hi Client")))
              case Unregistered                                    =>
                p.succeed(()) *>
                  printLine("Server Channel unregistered")
              case other                                           =>
                printLine("Server Unexpected: " + other)
            }
          }

        socketServer
      } { _ =>
        val socketClient: WebSocketApp[Any] =
          Handler.webSocket { channel =>
            channel.receiveAll {
              case ChannelEvent.Read(WebSocketFrame.Text("Hi Client")) =>
                channel.send(Read(WebSocketFrame.text("Hi Server")))
              case ChannelEvent.Unregistered                           =>
                printLine("Client Channel unregistered")
              case other                                               =>
                printLine("Client received Unexpected event: " + other)
            }
          }

        socketClient
      },
      contract("Application where server app fails")(_ =>
        Handler.webSocket { channel =>
          channel.receiveAll {
            case UserEventTriggered(UserEvent.HandshakeComplete) =>
              ZIO.fail(new Exception("Broken server"))
            case _                                               =>
              ZIO.unit
          }.ensuring(channel.shutdown)
        },
      ) { p =>
        Handler.webSocket { channel =>
          channel.receiveAll {
            case Unregistered =>
              printLine("Server failed and killed socket. Should complete promise.") *>
                p.succeed(()).unit
            case _            =>
              ZIO.unit
          }
        }
      },
      contract("Application where client app fails")(p =>
        Handler.webSocket { channel =>
          channel.receiveAll {
            case Unregistered =>
              printLine("Client failed and killed socket. Should complete promise.") *>
                p.succeed(()).unit
            case _            =>
              ZIO.unit
          }
        },
      ) { _ =>
        Handler.webSocket { channel =>
          channel.receiveAll {
            case UserEventTriggered(UserEvent.HandshakeComplete) =>
              ZIO.fail(new Exception("Broken client"))
            case _                                               => ZIO.unit
          }.ensuring(channel.shutdown)
        }
      },
    )

  private def contract(
    name: String,
  )(
    serverApp: Promise[Throwable, Unit] => WebSocketApp[Any],
  )(clientApp: Promise[Throwable, Unit] => WebSocketApp[Any]) = {
    suite(name)(
      test("Live") {
        for {
          portAndPromise <- liveServerSetup(serverApp)
          (port, promise) = portAndPromise
          url      <- ZIO.fromEither(URL.decode(s"ws://localhost:$port/")).orDie
          response <- ZIO.serviceWithZIO[Client](
            _.driver.socket(Version.Http_1_1, url, Headers.empty, clientApp(promise)),
          )
          _        <- promise.await.timeout(10.seconds)
        } yield assert(response.status)(equalTo(Status.SwitchingProtocols))
      }.provideSome[Client with Scope](
        TestServer.layer,
        NettyDriver.live,
        ZLayer.succeed(Server.Config.default.onAnyOpenPort),
      ).provideSome[Scope](Client.default),
      test("Test") {
        for {
          portAndPromise <- testServerSetup(serverApp)
          (port, promise) = portAndPromise
          url      <- ZIO.fromEither(URL.decode(s"ws://localhost:$port/")).orDie
          response <- ZIO.serviceWithZIO[Client](
            _.driver.socket(Version.Http_1_1, url, Headers.empty, clientApp(promise)),
          )
          _        <- promise.await.timeout(10.seconds)
        } yield assert(response.status)(equalTo(Status.SwitchingProtocols))
      }.provideSome[Scope](TestClient.layer),
    )
  }

  private def liveServerSetup(
    serverApp: Promise[Throwable, Unit] => WebSocketApp[Any],
  ): ZIO[Server, Nothing, (RuntimeFlags, Promise[Throwable, Unit])] =
    ZIO.serviceWithZIO[Server](server =>
      for {
        p <- Promise.make[Throwable, Unit]
        _ <- server.install(serverApp(p).toHttpAppWS)
      } yield (server.port, p),
    )

  private def testServerSetup(
    serverApp: Promise[Throwable, Unit] => WebSocketApp[Any],
  ): ZIO[TestClient, Nothing, (RuntimeFlags, Promise[Throwable, Unit])] =
    for {
      p <- Promise.make[Throwable, Unit]
      _ <- TestClient.installSocketApp(serverApp(p))
    } yield (0, p)

}
