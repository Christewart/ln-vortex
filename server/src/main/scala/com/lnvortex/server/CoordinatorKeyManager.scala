package com.lnvortex.server

import com.lnvortex.core.crypto.BlindSchnorrUtil
import com.lnvortex.server.config.VortexCoordinatorAppConfig
import com.lnvortex.server.models.AliceDAO
import grizzled.slf4j.Logging
import org.bitcoins.core.crypto.ExtKeyVersion.SegWitMainNetPriv
import org.bitcoins.core.crypto.ExtPrivateKeyHardened
import org.bitcoins.core.hd._
import org.bitcoins.crypto._
import org.bitcoins.keymanager._

import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec
import scala.concurrent._
import scala.concurrent.duration.DurationInt
import scala.util._

class CoordinatorKeyManager()(implicit
    ec: ExecutionContext,
    val config: VortexCoordinatorAppConfig)
    extends Logging {

  private[server] val aliceDAO = AliceDAO()

  /** The root private key for this coordinator */
  private[this] val extPrivateKey: ExtPrivateKeyHardened =
    WalletStorage.getPrivateKeyFromDisk(config.seedPath,
                                        SegWitMainNetPriv,
                                        config.aesPasswordOpt,
                                        config.bip39PasswordOpt)

  private val pubKeyPath = BIP32Path.fromHardenedString(
    s"m/${CoordinatorKeyManager.PURPOSE.constant}'/0'/0'")

  private val nonceCounter: AtomicInteger = {
    val startingIndex = Await.result(aliceDAO.nextNonceIndex(), 5.seconds)
    new AtomicInteger(startingIndex)
  }

  private def nextNoncePath: BIP32Path = {
    val purpose = CoordinatorKeyManager.PURPOSE
    val coin = HDCoin(purpose, HDCoinType.Bitcoin)
    val account = HDAccount(coin, 0)
    val hdChain = HDChain(HDChainType.External, account)
    val path = HDAddress(hdChain, nonceCounter.getAndIncrement()).toPath
    // make hardened for security
    // this is very important, otherwise can leak key data
    val hardened = path.map(_.copy(hardened = true))

    BIP32Path(hardened.toVector)
  }

  @tailrec
  final private[server] def nextNonce(): (SchnorrNonce, BIP32Path) = {
    val path = nextNoncePath
    val nonceT = extPrivateKey.deriveChildPubKey(nextNoncePath)

    nonceT match {
      case Success(nonce) => (nonce.key.schnorrNonce, path)
      case Failure(_)     => nextNonce()
    }
  }

  private[this] val privKey: ECPrivateKey =
    extPrivateKey.deriveChildPrivKey(pubKeyPath).key

  val publicKey: SchnorrPublicKey = privKey.schnorrPublicKey

  def createBlindSig(
      blindedOutput: FieldElement,
      noncePath: BIP32Path): FieldElement = {
    val nonceKey =
      extPrivateKey.deriveChildPrivKey(noncePath).key
    BlindSchnorrUtil.generateBlindSig(privKey, nonceKey, blindedOutput)
  }
}

object CoordinatorKeyManager {
  final val PURPOSE: HDPurpose = HDPurpose(69)
}