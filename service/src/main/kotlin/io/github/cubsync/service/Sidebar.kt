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
)

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

    fun local(): Local {
        val name = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("CubSync")
        val addresses = runCatching { localAddresses() }.getOrDefault(emptyList())
        return Local(name, addresses)
    }

    /** Every non-loopback IPv4 address, which is what a peer would type. */
    fun localAddresses(): List<String> = NetworkInterface.getNetworkInterfaces()
        .toList()
        .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
        .flatMap { card -> card.inetAddresses.toList().map { card.name to it } }
        .filter { (_, address) -> address is Inet4Address }
        .map { (_, address) -> address.hostAddress }
        .filterNotNull()
        .distinct()
        .sorted()
}
