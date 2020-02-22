package de.dytanic.cloudnetcore.network.packet.out;

import de.dytanic.cloudnet.lib.network.protocol.packet.Packet;
import de.dytanic.cloudnet.lib.network.protocol.packet.PacketRC;
import de.dytanic.cloudnet.lib.server.ProxyProcessMeta;
import de.dytanic.cloudnet.lib.utility.document.Document;

public class PacketOutStartProxy extends Packet {

    public PacketOutStartProxy(ProxyProcessMeta proxyProcessMeta) {
        super(PacketRC.CN_CORE + 1, new Document("proxyProcess", proxyProcessMeta));
    }

}
