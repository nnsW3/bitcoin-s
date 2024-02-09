package org.bitcoins.node.networking

import com.typesafe.config.ConfigFactory
import org.bitcoins.asyncutil.AsyncUtil
import org.bitcoins.node.NeutrinoNode
import org.bitcoins.server.BitcoinSAppConfig
import org.bitcoins.testkit.BitcoinSTestAppConfig
import org.bitcoins.testkit.node.fixture.NeutrinoNodeConnectedWithBitcoind
import org.bitcoins.testkit.node.{
  NodeTestUtil,
  NodeTestWithCachedBitcoindNewest
}
import org.scalatest.FutureOutcome

import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, FiniteDuration}

class ReConnectionTest extends NodeTestWithCachedBitcoindNewest {

  override protected def getFreshConfig: BitcoinSAppConfig =
    BitcoinSTestAppConfig.getNeutrinoWithEmbeddedDbTestConfig(() => pgUrl(),
                                                              Vector.empty)

  override type FixtureParam = NeutrinoNodeConnectedWithBitcoind

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    val f = for {
      bitcoind <- cachedBitcoindWithFundsF
      outcome <- withNeutrinoNodeUnstarted(test, bitcoind)(
        system,
        getFreshConfig).toFuture
    } yield outcome

    new FutureOutcome(f)
  }

  behavior of "ReConnectionTest"

  it must "disconnect a peer after a period of inactivity" in {
    nodeConnectedWithBitcoind: NeutrinoNodeConnectedWithBitcoind =>
      val bitcoind = nodeConnectedWithBitcoind.bitcoind
      val timeout = 5.seconds
      val startedF =
        getSmallInactivityCheckNeutrinoNode(nodeConnectedWithBitcoind.node,
                                            timeout)
      for {
        started <- startedF
        _ <- NodeTestUtil.awaitConnectionCount(node = started,
                                               expectedConnectionCount = 1)
        //let sync occur so we aren't receiving blockchain data over the network
        _ <- NodeTestUtil.awaitAllSync(started, bitcoind)
        //wait until there is a timeout for inactivity
        _ <- AsyncUtil.nonBlockingSleep(timeout)
        _ <- NodeTestUtil.awaitConnectionCount(node = started,
                                               expectedConnectionCount = 0)
        _ <- started.stop()
        _ <- started.nodeConfig.stop()
      } yield {
        succeed
      }
  }

  it must "reconnect a peer when inactivity checks run and we have 0 peers" in {
    nodeConnectedWithBitcoind: NeutrinoNodeConnectedWithBitcoind =>
      //see: https://github.com/bitcoin-s/bitcoin-s/issues/5162
      val bitcoind = nodeConnectedWithBitcoind.bitcoind
      val timeout = 5.second
      val startedF =
        getSmallInactivityCheckNeutrinoNode(nodeConnectedWithBitcoind.node,
                                            timeout)
      for {
        started <- startedF
        _ <- NodeTestUtil.awaitConnectionCount(started, 1)
        //explicitly disconnect it
        bitcoindPeer <- NodeTestUtil.getBitcoindPeer(bitcoind)
        _ <- started.peerManager.disconnectPeer(bitcoindPeer)
        //wait until we have zero connections
        _ <- NodeTestUtil.awaitConnectionCount(started, 0)

        //wait until there is a timeout for inactivity
        _ <- NodeTestUtil.awaitConnectionCount(started, 1)
        _ <- started.peerManager.isConnected(bitcoindPeer)
        _ <- started.stop()
        _ <- started.nodeConfig.stop()
      } yield {
        succeed
      }
  }

  private def getSmallInactivityCheckNeutrinoNode(
      initNode: NeutrinoNode,
      timeout: FiniteDuration): Future[NeutrinoNode] = {

    //make a custom config, set the inactivity timeout very low
    //so we will disconnect our peer organically
    val config =
      ConfigFactory.parseString(
        s"bitcoin-s.node.inactivity-timeout=${timeout.toString}")
    val stoppedConfigF = initNode.nodeConfig.stop()
    val newNodeAppConfigF =
      stoppedConfigF.map(_ => initNode.nodeConfig.withOverrides(config))
    val nodeF = {
      for {
        newNodeAppConfig <- newNodeAppConfigF
        _ <- newNodeAppConfig.start()
      } yield {
        NeutrinoNode(
          walletCreationTimeOpt = initNode.walletCreationTimeOpt,
          nodeConfig = newNodeAppConfig,
          chainConfig = initNode.chainAppConfig,
          actorSystem = initNode.system,
          paramPeers = initNode.paramPeers
        )
      }
    }

    val startedF = nodeF.flatMap(_.start())
    startedF
  }
}
