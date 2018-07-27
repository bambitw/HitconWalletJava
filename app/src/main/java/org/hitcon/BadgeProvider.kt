package org.hitcon

import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.util.Log
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import org.hitcon.activities.HitconBadgeActivity.Companion.KeyTransaction
import org.hitcon.data.badge.BadgeEntity
import org.hitcon.data.badge.BadgeServiceEntity
import org.hitcon.data.badge.getLastBadge
import org.hitcon.data.badge.upsertBadge
import org.hitcon.data.qrcode.InitializeContent
import org.hitcon.helper.toHex
import org.kethereum.model.Transaction
import org.walleth.data.AppDatabase
import org.walleth.data.tokens.Token
import org.walleth.data.tokens.isHITCON
import org.walleth.functions.toHexString
import org.walleth.khex.clean0xPrefix
import org.walleth.khex.hexToByteArray
import org.walleth.khex.toHexString
import org.walleth.khex.toNoPrefixHexString
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

fun Intent.getTxn() = getStringExtra(BadgeProvider.KeyTxn)
fun String.padZero() = if (this.length % 2 == 0) this else "0$this"
fun Double.toByteArray() = ByteBuffer.allocate(8).putDouble(this).array().reversed()

enum class HitconBadgeServices {
    Transaction,
    Txn,
    AddERC20,
    Balance,
    GeneralPurposeCmd,
    GeneralPurposeData
}

@Suppress("DEPRECATION")
/**
 * Hitcon Badge Service Provider
 */
class BadgeProvider(private val context: Context, private val appDatabase: AppDatabase) : Handler() {
    companion object {
        const val TAG = "HitconBadge"
        const val InitializeBadgeProvider = 0
        const val MessageReceiveTxn = 1
        const val ActionReceiveTxn = "Action_ReceiveTxn"
        const val MessageGattConnectionChanged = 2
        const val MessageMtuFailure = 3
        const val MessageStopScanDevices = 4
        const val MessageStartScanGattService = 5
        const val KeyTxn = "TXN"
        val serviceNames = arrayOf(
                HitconBadgeServices.Transaction,
                HitconBadgeServices.Txn,
                HitconBadgeServices.AddERC20,
                HitconBadgeServices.Balance,
                HitconBadgeServices.GeneralPurposeCmd,
                HitconBadgeServices.GeneralPurposeData)
    }

    var entity: BadgeEntity? = null
    var device: BluetoothDevice? = null
    var scanning: Boolean = false
    val services: LinkedHashMap<HitconBadgeServices, BluetoothGattCharacteristic> = LinkedHashMap()
    var connected: Boolean = false
    var serviceBound: Boolean = false

    private var scanDeviceCallback: BadgeScanCallback? = null
    private var scanDeviceCallback2: BadgeScanCallbackNew? = null
    private var gattScanCallback: GattScanCallback? = null
    private val delayStopScanRunnable = Runnable { stopScanDevice(true) }

    private var gatt: BluetoothGatt? = null
    private var adapter: BluetoothAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var leScanner: BluetoothLeScanner? = adapter.bluetoothLeScanner
    private var mtu = 512
    private val delay = 15 * 1000L
    private var txIV: ByteArray = ByteArray(16)
    private var baIV: ByteArray = ByteArray(16)
    private var ethIV: ByteArray = ByteArray(16)
    private var transaction: Transaction? = null
    private var transacting = transaction != null

    init {
        async(UI) {
            async(CommonPool) {
                entity = appDatabase.badges.getLastBadge()
            }.await()
        }
    }

    override fun handleMessage(msg: Message?) {
        when (msg?.what) {

            MessageStopScanDevices -> {
                stopScanDevice()
            }
            MessageMtuFailure -> {
            }
            MessageReceiveTxn -> {
                val bytes = msg.data.getByteArray(KeyTxn)
                val txn = bytes.toHexString()
                Log.d(TAG, "Tx Hex: $txn")
                context.sendBroadcast(Intent().apply {
                    action = ActionReceiveTxn
                    putExtra(KeyTxn, txn)
                })
            }
            MessageGattConnectionChanged -> {
            }
            MessageStartScanGattService -> {
                device?.let {
                    startConnectGatt(it)
                }
            }
        }

    }


    interface BadgeCallback {
        fun onDeviceFound(device: BluetoothDevice)
        fun onTimeout()
        fun onServiceDiscovered(bound: Boolean)
        fun onMtuChanged()
    }

    private fun getServiceEntityList(init: InitializeContent): List<BadgeServiceEntity> {
        val list = ArrayList<BadgeServiceEntity>()
        for (name in serviceNames)
            list.add(BadgeServiceEntity(init.service, name.name, init.getUUID(name)))
        return list
    }

    /**
     * Android Version < 21 Adapter Scanner
     */
    private class BadgeScanCallback(val badgeProvider: BadgeProvider, val badgeCallback: BadgeCallback?) : BluetoothAdapter.LeScanCallback {
        override fun onLeScan(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?) {
            Log.d(TAG, "onLeScan: ${device?.toString()}")
            val arrayList = parseUUIDList(scanRecord!!)
            if (arrayList.size > 0 && arrayList.any { u -> u.toString() == badgeProvider.entity?.identify }) {
                badgeProvider.device = device
                device?.let { badgeCallback?.onDeviceFound(it) }
                badgeProvider.sendEmptyMessage(MessageStopScanDevices)
            }
        }

        private fun parseUUIDList(bytes: ByteArray): ArrayList<UUID> {
            val list = ArrayList<UUID>()
            var offset = 0
            while (offset < bytes.size - 2) {
                var len = bytes[offset++].toInt()
                if (len == 0)
                    break

                val type = bytes[offset++].toInt()
                when (type) {
                    0x02, 0x03 -> {
                        while (len > 1) {
                            var uuid16 = bytes[offset++].toInt()
                            uuid16 += bytes[offset++].toInt() shl 8
                            len -= 2
                            list.add(UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", uuid16)))
                        }
                    }
                    0x06, 0x07 ->
                        while (len >= 16) {
                            try {
                                val buffer = ByteBuffer.wrap(bytes, offset++, 16).order(ByteOrder.LITTLE_ENDIAN)
                                val mostSignificantBit = buffer.getLong()
                                val leastSignificantBit = buffer.getLong()
                                list.add(UUID(leastSignificantBit, mostSignificantBit))
                            } catch (e: IndexOutOfBoundsException) {
                                continue
                            } finally {
                                offset += 15
                                len -= 16
                            }
                        }
                    else -> offset += len - 1
                }
            }
            return list
        }
    }

    /***
     * Android Version > 21 LeScanner Callback
     */
    private class BadgeScanCallbackNew(val badgeProvider: BadgeProvider, val badgeCallback: BadgeCallback?) : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            Log.d(TAG, "onBadgeScanResult: ${result?.scanRecord?.toString()}")
            result?.scanRecord?.serviceUuids?.let {
                if (it.size > 0 && it.any { u -> u.toString() == badgeProvider.entity?.identify }) {
                    badgeProvider.device = result.device
                    result.device?.let { badgeCallback?.onDeviceFound(it) }
                    badgeProvider.sendEmptyMessage(MessageStopScanDevices)
                }
            }
        }
    }

    /**
     * Gatt callback
     */
    private class GattScanCallback(val badgeProvider: BadgeProvider, val badgeCallback: BadgeCallback?) : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            Log.i(TAG, "onConnectionStateChange: $status -> $newState")
            badgeProvider.connected = newState == BluetoothGatt.STATE_CONNECTED
            badgeProvider.sendEmptyMessage(MessageGattConnectionChanged)
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    Log.d(TAG, "start discoverService")
                    gatt?.discoverServices()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            Log.i(TAG, "onMtuChanged, success: ${status == BluetoothGatt.GATT_SUCCESS}")
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    Log.d(TAG, "mtu change to $mtu")
                    badgeProvider.mtu = mtu
                    badgeCallback?.onMtuChanged()
                }
                BluetoothGatt.GATT_FAILURE -> {
                    Log.e(TAG, "mtu fail: $mtu")
                    val tmp = mtu / 2
                    if (tmp < 128)
                        badgeProvider.sendEmptyMessage(MessageMtuFailure)
                    else {
                        Log.e(TAG, "reset mtu to $tmp")
                        gatt?.requestMtu(tmp)
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            Log.i(TAG, "onServicesDiscovered, success: ${status == BluetoothGatt.GATT_SUCCESS}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                badgeProvider.services.clear()
                Log.d(TAG, "pair service: ${badgeProvider.entity?.identify}")
                for (service in gatt!!.services) {
                    Log.d(TAG, "matching service: ${service.uuid}")
                    if (service.uuid.toString() == badgeProvider.entity?.identify) {
                        for (ch in service.characteristics) {
                            Log.d(TAG, "matching characteristic: ${ch.uuid}")
                            val name = badgeProvider.entity?.getUuidName(ch.uuid)
                            if (name != null) {
                                Log.d(TAG, "Binding!")
                                badgeProvider.services[name] = ch
                            }
                        }
                        badgeProvider.serviceBound = badgeProvider.services.size > 0
                    }
                }
                badgeCallback?.onServiceDiscovered(badgeProvider.serviceBound)
                Log.d(TAG, "start change mtu: ${badgeProvider.mtu}")
                gatt.requestMtu(badgeProvider.mtu)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            if (characteristic?.uuid == badgeProvider.services[HitconBadgeServices.Txn]?.uuid)
                gatt?.readCharacteristic(characteristic)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            if (characteristic?.uuid == badgeProvider.services[HitconBadgeServices.Txn]?.uuid) {
                badgeProvider.sendMessage(Message().apply {
                    val value = characteristic?.value?.clone()
                    Log.e(TAG, "Receive characteristic: ${value?.toNoPrefixHexString()}")
                    what = MessageReceiveTxn
                    data = Bundle().apply { putByteArray(KeyTxn, value) }
                })
            }

        }


    }

    /**
     * Enable notification
     */
    private fun enableNotifications(characteristic: BluetoothGattCharacteristic): Boolean {
        Log.d(TAG, "enableNotification: ${characteristic.uuid}")
        gatt?.setCharacteristicNotification(characteristic, true)
        val desc = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return gatt?.writeDescriptor(desc) ?: false
    }

    /**
     * Stat scan device, use initialize content
     */
    fun startScanDevice(badgeCallback: BadgeCallback? = null) {
        Log.i(TAG, "... startScanDevice .... ")
        if (scanning) stopScanDevice()
        scanning = true
        if (leScanner == null) {
            scanDeviceCallback = BadgeScanCallback(this, badgeCallback)
            adapter.startLeScan(scanDeviceCallback)
        } else {
            scanDeviceCallback2 = BadgeScanCallbackNew(this, badgeCallback)
            leScanner!!.startScan(scanDeviceCallback2)
        }
        postDelayed(delayStopScanRunnable, delay)
    }

    fun startConnectGatt(device: BluetoothDevice, leScanCallback: BadgeCallback? = null) {
        Log.i(TAG, "... startConnectGatt .... ")
        if (connected) {
            Log.d(TAG, "Gatt is connected, disconnect first")
            gatt?.disconnect()
            gatt = null
        }
            Log.d(TAG, "no gatt instance, connect create")
            gattScanCallback = GattScanCallback(this, leScanCallback)
            serviceBound = false
            gatt = device.connectGatt(context, false, gattScanCallback)

            postDelayed({
                if (!serviceBound) {
                    gatt?.disconnect()
                    leScanCallback?.onTimeout()
                }
            }, 15000)

        //gatt?.disconnect()
        //gatt?.connect()
        gatt?.let { refreshGatt(it) }
    }

    /**
     * Stop scan, if timeout then call callback
     */
    private fun stopScanDevice(timeout: Boolean = false) {
        if (scanning) {
            Log.i(TAG, "stopScanDevice, timeout flag: $timeout")
            if (timeout) {
                scanDeviceCallback?.badgeCallback?.onTimeout()
                scanDeviceCallback2?.badgeCallback?.onTimeout()
            } else {
                removeCallbacks(delayStopScanRunnable)
            }

            scanDeviceCallback?.let { adapter.stopLeScan(it) }
            scanDeviceCallback2?.let { leScanner?.stopScan(it) }
            scanning = false
        }
    }

    fun saveEntity() {
        entity?.let {
            async(UI) {
                async(CommonPool) {
                    appDatabase.badges.upsertBadge(it)
                }.await()
            }
        }
    }

    private fun refreshGatt(gatt: BluetoothGatt) {
        //add_info("Connected!\n");
        try {
            val method = gatt.javaClass.getMethod("refresh")
            method?.invoke(gatt)
        } catch (e: NoSuchMethodException) {
            e.printStackTrace()
        } catch (e: IllegalAccessException) {
            e.printStackTrace()
        } catch (e: InvocationTargetException) {
            e.printStackTrace()
        }
    }


    fun startTransaction(transaction: org.kethereum.model.Transaction) {
        Log.i(TAG, "start transaction")
        val haddress = transaction.to.toString().clean0xPrefix()
        val hvalue = transaction.value.toHexString().clean0xPrefix().padZero()
        val hgaslimit = transaction.gasLimit.toHexString().clean0xPrefix().padZero()
        val hgas = transaction.gasPrice.toHexString().clean0xPrefix().padZero()
        val hnoice = transaction.nonce!!.toHexString().clean0xPrefix().padZero()
        val hdata = transaction.input.toHexString("")
        val transArray =
                "01" + String.format("%02X", haddress.length / 2) + haddress +
                        "02" + String.format("%02X", hvalue.length / 2) + hvalue +
                        "03" + String.format("%02X", hgas.length / 2) + hgas +
                        "04" + String.format("%02X", hgaslimit.length / 2) + hgaslimit +
                        "05" + String.format("%02X", hnoice.length / 2) + hnoice +
                        "06" + String.format("%02X", hdata.length / 2) + hdata
        Log.d(TAG, "01(haddress): $haddress")
        Log.d(TAG, "02(hvalue): $hvalue")
        Log.d(TAG, "03(hgas): $hgas")
        Log.d(TAG, "04(hgaslimit): $hgaslimit")
        Log.d(TAG, "05(hnoice): $hnoice")
        Log.d(TAG, "06(hdata): $hdata")
        Log.d(TAG, "transArray: $transArray")
        SecureRandom().nextBytes(txIV)
        val key = entity!!.key!!.hexToByteArray()
        val text = transArray.hexToByteArray()
        val enc = (txIV.toHexString("") + encrypt(txIV, key, text).toHexString("")).hexToByteArray()
        Log.d(TAG, "enc(transArray): ${enc.toHexString()}")
        val cha = services[HitconBadgeServices.Transaction]
        cha?.value = enc
        gatt?.writeCharacteristic(cha)
        gatt?.setCharacteristicNotification(cha!!, true)
        enableNotifications(services[HitconBadgeServices.Txn]!!)
    }

    private fun getTransArrayBalance(haddress: String, hvalue: String): String {
        return "01" + String.format("%02X", haddress.length / 2) + haddress +
                "02" + String.format("%02X", hvalue.length / 2) + hvalue
    }

    fun startUpdateBalance(ethBalance: String?, hitconBalance: String?, token: Token) {
        if (entity == null || !connected || transacting) return
        Log.i(TAG, "start update balance, eth raw: '$ethBalance', hitcon raw: '$hitconBalance'")

        var transArray = ""
        if (ethBalance != null) {
            val haddress = entity!!.address!!
            val balanceValue = ethBalance.toBigDecimal().scaleByPowerOfTen(-18).toDouble()
            val hvalue = balanceValue.toByteArray().toHexString().clean0xPrefix()
            val trans = getTransArrayBalance(haddress, hvalue)
            Log.d(TAG, "trans eth raw: $trans")
            transArray += trans
        }

        if (hitconBalance != null && token.isHITCON()) {
            val haddress = token.address.cleanHex
            val balanceValue = hitconBalance.toBigDecimal().scaleByPowerOfTen(-token.decimals).toDouble()
            val hvalue = balanceValue.toByteArray().toHexString().clean0xPrefix()
            val trans = getTransArrayBalance(haddress, hvalue)
            Log.d(TAG, "trans hitcon token raw: $trans")
            transArray += trans
        }

        if (transArray.isNotEmpty()) {
            Log.d(TAG, "raw transArray is $transArray, start update")
            SecureRandom().nextBytes(ethIV)
            val key = entity!!.key!!.hexToByteArray()
            val text = transArray.hexToByteArray()
            val enc = (ethIV.toHex() + encrypt(ethIV, key, text).toHex()).hexToByteArray()
            val cha = services[HitconBadgeServices.Balance]
            cha?.value = enc
            cha?.let { gatt?.writeCharacteristic(it) }
        } else
            Log.d(TAG, "no data need update, do nothing")
        Log.i(TAG, "finish update balance")
    }

    fun initializeBadge(init: InitializeContent, leScanCallback: BadgeCallback? = null) {
        entity = BadgeEntity(init.service, null, init.address, key = init.key, services = getServiceEntityList(init))
        saveEntity()
        startScanDevice(leScanCallback)
    }

    fun disconnectBadge() {
        if (connected) {
            gatt?.disconnect()
        }
        gatt = null

    }


    private fun getDecryptText(cyber: ByteArray): String {
        return decrypt(txIV, entity!!.key!!.toByteArray(), cyber).toHexString()
    }


    private fun encrypt(iv: ByteArray, key: ByteArray, text: ByteArray): ByteArray {
        return try {
            val algParamSpec = IvParameterSpec(iv)
            val secretKeySpe = SecretKeySpec(key, "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.ENCRYPT_MODE, secretKeySpe, algParamSpec)
            }
            cipher.doFinal(text)
        } catch (ex: Exception) {
            ByteArray(0)
        }

    }

    private fun decrypt(iv: ByteArray, key: ByteArray, cyber: ByteArray): ByteArray {
        return try {
            val algParamSpec = IvParameterSpec(iv)
            val secretKeySpe = SecretKeySpec(key, "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.DECRYPT_MODE, secretKeySpe, algParamSpec)
            }
            cipher.doFinal(cyber)
        } catch (ex: Exception) {
            ByteArray(0)
        }

    }
}


