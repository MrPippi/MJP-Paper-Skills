package com.example.probe.command;

import com.example.probe.Probe;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * paper-brigadier-command (Example plugin, its own jar): commands registered through LifecycleEvents.COMMANDS,
 * run by a non-player sender that records what it is told.
 */
public final class CommandProbe extends Probe {

    private final List<String> feedback = new ArrayList<>();

    @Override
    protected void start() throws Exception {
        Plugin example = getServer().getPluginManager().getPlugin("Example");
        check("Example enabled", example != null && example.isEnabled());
        Map<String, Command> known = getServer().getCommandMap().getKnownCommands();
        check("/pay registered", known.containsKey("pay"));
        check("/transfer alias registered", known.containsKey("transfer"));
        check("/example registered", known.containsKey("example"));
        check("namespaced /example:pay registered", known.containsKey("example:pay"));

        CommandSender sender = getServer().createCommandSender(c -> feedback.add(PlainTextComponentSerializer.plainText().serialize(c)));
        log("feedback sender: op=" + sender.isOp() + " example.admin=" + sender.hasPermission("example.admin"));

        check("/example prints its help", run(sender, "example").contains("Example plugin."));

        Files.writeString(example.getDataFolder().toPath().resolve("config.yml"), "marker: 2\n");
        check("/example reload answers", run(sender, "example reload").contains("Configuration reloaded."));
        check("/example reload really reloaded config.yml", example.getConfig().getInt("marker") == 2);

        // 26.x: getPlayerOrThrow() throws vanilla's message; 1.21.11: the template's own null check
        String notPlayer = run(sender, "pay Notch 1.5k");
        check("/pay from a non-player -> player-required error",
                notPlayer.contains("A player is required") || notPlayer.contains("This command can only be used by a player."));
        // An error thrown while converting a custom argument is not sent to the sender by Bukkit.dispatchCommand:
        // it reaches the caller as a CommandException (errors thrown from executes() are sent, see above)
        CommandException thrown = null;
        try {
            run(sender, "pay Notch abc");
        } catch (CommandException e) {
            thrown = e;
        }
        log("/pay Notch abc -> " + thrown);
        check("dispatchCommand: argument error reaches the caller as CommandException", thrown != null);
        Throwable cause = thrown == null ? null : thrown.getCause();
        check("argument error carries the custom message", cause != null && String.valueOf(cause.getMessage()).contains("Invalid amount abc"));

        List<String> suggestions = getServer().getCommandMap().tabComplete(sender, "pay Notch 2");
        log("suggestions for 'pay Notch 2': " + suggestions);
        check("amount suggestions 2k / 2m / 2b", suggestions != null && suggestions.containsAll(List.of("2k", "2m", "2b")));
        finish();
    }

    /** Dispatches a command and returns everything the sender was told. */
    private String run(CommandSender sender, String command) {
        feedback.clear();
        boolean handled = getServer().dispatchCommand(sender, command);
        String told = String.join(" | ", feedback);
        log("/" + command + " -> handled=" + handled + ": " + told);
        return told;
    }
}
