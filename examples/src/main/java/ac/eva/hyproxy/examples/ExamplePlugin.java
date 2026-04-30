package ac.eva.hyproxy.examples;


import ac.eva.hyproxy.HyProxy;
import ac.eva.hyproxy.event.impl.player.PlayerAuthSuccessEvent;
import ac.eva.hyproxy.examples.commands.TestCommand;
import ac.eva.hyproxy.plugin.HyProxyPlugin;

public class ExamplePlugin implements HyProxyPlugin {

    @Override
    public void load(HyProxy proxy) {
        proxy.getCommandManager().registerCloudAnnotationCommand(new TestCommand());
        proxy.getEventBus().subscribe(PlayerAuthSuccessEvent.class, 0, event -> {
            System.out.println("Hello: " + event.getPlayer().getUsername());
        });
    }
}
