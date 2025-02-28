package com.lnvortex.server

import com.lnvortex.client.RoundDetails.getInitDetailsOpt
import com.lnvortex.testkit.{DualClientFixture, LnVortexTestUtils}
import org.bitcoins.testkit.EmbeddedPg
import org.bitcoins.testkit.async.TestAsyncUtil

import scala.concurrent.duration.{DurationInt, FiniteDuration}

class DualClientTest extends DualClientFixture with EmbeddedPg {

  val interval: FiniteDuration =
    if (LnVortexTestUtils.torEnabled) 500.milliseconds else 100.milliseconds

  it must "get nonces from the coordinator" in {
    case (clientA, clientB, coordinator) =>
      for {
        nonceA <- clientA.askNonce()
        nonceB <- clientB.askNonce()
        aliceDbs <- coordinator.aliceDAO.findAll()
      } yield {
        assert(aliceDbs.size == 2)
        assert(aliceDbs.exists(_.nonce == nonceA))
        assert(aliceDbs.exists(_.nonce == nonceB))
      }
  }

  it must "register inputs" in { case (clientA, clientB, coordinator) =>
    for {
      nodeIdA <- clientA.vortexWallet.lndRpcClient.nodeId
      nodeIdB <- clientB.vortexWallet.lndRpcClient.nodeId
      _ <- clientA.askNonce()
      _ <- clientB.askNonce()
      // don't select all coins
      utxosA <- clientA.listCoins.map(_.tail)
      _ = clientA.queueCoins(utxosA.map(_.outputReference), nodeIdB, None)
      utxosB <- clientB.listCoins.map(_.tail)
      _ = clientB.queueCoins(utxosB.map(_.outputReference), nodeIdA, None)
      _ <- coordinator.beginInputRegistration()
      // give time for messages to send
      _ <- TestAsyncUtil.awaitConditionF(
        () =>
          coordinator.inputsDAO
            .findAll()
            .map(_.size == utxosA.size + utxosB.size),
        interval = interval,
        maxTries = 500)
    } yield succeed
  }

  it must "register inputs & outputs" in {
    case (clientA, clientB, coordinator) =>
      for {
        nodeIdA <- clientA.vortexWallet.lndRpcClient.nodeId
        nodeIdB <- clientB.vortexWallet.lndRpcClient.nodeId
        _ <- clientA.askNonce()
        _ <- clientB.askNonce()
        // don't select all coins
        utxosA <- clientA.listCoins.map(_.tail)
        _ = clientA.queueCoins(utxosA.map(_.outputReference), nodeIdB, None)
        utxosB <- clientB.listCoins.map(_.tail)
        _ = clientB.queueCoins(utxosB.map(_.outputReference), nodeIdA, None)
        _ <- coordinator.beginInputRegistration()
        // wait until outputs are registered
        _ <- TestAsyncUtil.awaitConditionF(
          () =>
            coordinator.inputsDAO
              .findAll()
              .map(_.size == utxosA.size + utxosB.size),
          interval = interval,
          maxTries = 500)

        // expected outputs should be set now, save for assertions
        expectedOutputA =
          getInitDetailsOpt(clientA.getCurrentRoundDetails).get.mixOutput
        expectedOutputB =
          getInitDetailsOpt(clientB.getCurrentRoundDetails).get.mixOutput

        _ <- TestAsyncUtil.awaitConditionF(
          () => coordinator.outputsDAO.findAll().map(_.size == 2),
          interval = interval,
          maxTries = 500)
        outputDbs <- coordinator.outputsDAO.findAll()
      } yield {
        assert(outputDbs.size == 2)
        assert(outputDbs.exists(_.output == expectedOutputA))
        assert(outputDbs.exists(_.output == expectedOutputB))
      }
  }

  it must "sign the psbt" in { case (clientA, clientB, coordinator) =>
    for {
      nodeIdA <- clientA.vortexWallet.lndRpcClient.nodeId
      nodeIdB <- clientB.vortexWallet.lndRpcClient.nodeId
      roundId = coordinator.getCurrentRoundId

      _ <- clientA.askNonce()
      _ <- clientB.askNonce()
      // don't select all coins
      utxosA <- clientA.listCoins.map(_.tail)
      _ = clientA.queueCoins(utxosA.map(_.outputReference), nodeIdB, None)
      utxosB <- clientB.listCoins.map(_.tail)
      _ = clientB.queueCoins(utxosB.map(_.outputReference), nodeIdA, None)
      _ <- coordinator.beginInputRegistration()
      // wait until outputs are registered
      _ <- TestAsyncUtil.awaitConditionF(
        () =>
          coordinator.inputsDAO
            .findAll()
            .map(_.size == utxosA.size + utxosB.size),
        interval = interval,
        maxTries = 500)
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.outputsDAO.findAll().map(_.size == 2),
        interval,
        maxTries = 500)
      // wait until we construct the unsigned tx
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.getRound(roundId).map(_.psbtOpt.isDefined),
        interval = interval,
        maxTries = 500)

      // wait until the tx is signed
      // use getRound because we could start the new round
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.getRound(roundId).map(_.transactionOpt.isDefined),
        interval = interval,
        maxTries = 500)
    } yield succeed
  }

  it must "open the channels" in { case (clientA, clientB, coordinator) =>
    for {
      nodeIdA <- clientA.vortexWallet.lndRpcClient.nodeId
      nodeIdB <- clientB.vortexWallet.lndRpcClient.nodeId
      roundId = coordinator.getCurrentRoundId

      _ <- clientA.askNonce()
      _ <- clientB.askNonce()
      _ <- coordinator.beginInputRegistration()
      // don't select all coins
      utxosA <- clientA.listCoins.map(_.tail)
      _ = clientA.queueCoins(utxosA.map(_.outputReference), nodeIdB, None)
      utxosB <- clientB.listCoins.map(_.tail)
      _ = clientB.queueCoins(utxosB.map(_.outputReference), nodeIdA, None)
      _ <- coordinator.beginInputRegistration()
      // wait until outputs are registered
      _ <- TestAsyncUtil.awaitConditionF(
        () =>
          coordinator.inputsDAO
            .findAll()
            .map(_.size == utxosA.size + utxosB.size),
        interval = interval,
        maxTries = 500)
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.outputsDAO.findAll().map(_.size == 2),
        interval = interval,
        maxTries = 500)
      // wait until we construct the unsigned tx
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.getRound(roundId).map(_.psbtOpt.isDefined),
        interval = interval,
        maxTries = 500)

      // wait until the tx is signed
      // use getRound because we could start the new round
      _ <- TestAsyncUtil.awaitConditionF(
        () => coordinator.getRound(roundId).map(_.transactionOpt.isDefined),
        interval = interval,
        maxTries = 500)

      // Mine some blocks
      _ <- coordinator.bitcoind.getNewAddress.flatMap(
        coordinator.bitcoind.generateToAddress(6, _))

      // wait until clientA sees new channels
      _ <- TestAsyncUtil.awaitConditionF(
        () => clientA.vortexWallet.lndRpcClient.listChannels().map(_.size == 2),
        interval = interval,
        maxTries = 500)

      // wait until clientB sees new channels
      _ <- TestAsyncUtil.awaitConditionF(
        () => clientB.vortexWallet.lndRpcClient.listChannels().map(_.size == 2),
        interval = interval,
        maxTries = 500)

      aliceDbs <- coordinator.aliceDAO.findByRoundId(roundId)
      outputDbs <- coordinator.outputsDAO.findByRoundId(roundId)
    } yield {
      assert(outputDbs.nonEmpty)
      val noNonceMatching = outputDbs.forall { db =>
        val nonce = db.sig.rx
        !aliceDbs.exists(_.nonce == nonce)
      }

      assert(noNonceMatching)
    }
  }
}
