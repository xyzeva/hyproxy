package ac.eva.hyproxy.examples.commands;

import ac.eva.hyproxy.message.Message;
import ac.eva.hyproxy.player.HyProxyPlayer;
import org.incendo.cloud.annotations.Command;

import java.awt.*;

public class TestCommand {

    @Command("example test")
    public void testCommand(HyProxyPlayer sender) {
        sender.sendMessage(Message.raw("example test").color(Color.GREEN));
    }

}
