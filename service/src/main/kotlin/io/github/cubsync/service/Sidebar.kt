package io.github.cubsync.service

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The library's own way of naming a folder so it can be got back to.
 *
 * [device] is what makes the drawer's grouping meaningful: a favourite is a place on a
 * machine, not a place on the screen, and a list that mixes the two is a list nobody
 * can scan.
 */
data class Favorite(
    val device: String,
    val backendId: String,
    val path: String,
    val label: String = PathTools.leafName(path).ifEmpty { path },
    /**
     * Which protocol answers for this end: 本端 for a local disk, CubSync for a peer, and
     * the name of whatever comes next (WebDAV, FTP, SFTP) once those backends exist.
     *
     * A favourite is a place on a machine rather than a place on a screen, and on a machine
     * reachable two ways it is the protocol that says which one.
     */
    val protocol: String = if (backendId == EndpointId.LOCAL) "本端" else "CubSync",
) {
    /** The heading this favourite belongs under. */
    val group: String get() = if (backendId == EndpointId.LOCAL) Words.LOCAL else "$device · $protocol"
}

data class FavoriteGroup(val device: String, val items: List<Favorite>)

/** One row of the drawer's account section. */
data class AccountEntry(
    val label: String,
    val role: String,
    val online: Boolean,
)

/**
 * The contents of the left-swipe drawer.
 *
 * Built from state the application already has rather than from a second store, so the
 * drawer cannot show a folder that is no longer shared, or claim a peer is online when
 * the socket has gone.
 */
data class DrawerModel(
    val favourites: List<FavoriteGroup>,
    val accounts: List<AccountEntry>,
    val roots: List<Favorite>,
)

// --------------------------------------------------------------------- peers

/** A machine this one has talked to, or could. */
data class KnownPeer(
    val name: String,
    val host: String,
    val port: Int,
    val seenAtMs: Long = 0,
) {
    val address: String get() = "$host:$port"
}

/**
 * The machines offered on the connect screen.
 *
 * There is no discovery broadcast here on purpose. Advertising a file server on a
 * network nobody asked about is a decision with consequences, and inventing a list of
 * "discovered" devices that were really a hard-coded sample would be worse than
 * offering what is true: this machine, and the peers it has actually reached.
 */
object Peers {

    /** A name and address that can be shown as this machine. */
    data class Local(val name: String, val addresses: List<String>)

    /**
     * One network card, as 传输通道 lists it.
     *
     * The name is the system's (`wlan0`, `rmnet_data0`), which is what the interface is
     * actually called; [kind] is what a person would call it.
     */
    data class Nic(val name: String, val address: String) {
        val kind: String
            get() = when {
                name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("wireless") -> "Wi-Fi"
                name.startsWith("eth") || name.startsWith("ethernet") ||
                    name.startsWith("usb") || name.startsWith("rndis") -> "有线 / USB"
                name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("radio") -> "移动网络"
                name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("wg") -> "VPN"
                else -> name
            }
    }

    fun local(): Local {
        val name = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("CubSync")
        val addresses = runCatching { localAddresses() }.getOrDefault(emptyList())
        return Local(name, addresses)
    }

    /** Every card that is up, with the address it can be reached at. */
    fun localNics(): List<Nic> = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { card -> card.inetAddresses.toList().filterIsInstance<Inet4Address>().map { card.name to it } }
            .mapNotNull { (name, address) -> address.hostAddress?.let { Nic(name, it) } }
            .filterNot { it.address.startsWith("169.254.") }
            .distinctBy { it.address }
            .sortedWith(compareBy({ it.kind }, { it.address }))
    }.getOrDefault(emptyList())

    /** Every non-loopback IPv4 address, which is what a peer would type. */
    fun localAddresses(): List<String> = localNics().map { it.address }
}
