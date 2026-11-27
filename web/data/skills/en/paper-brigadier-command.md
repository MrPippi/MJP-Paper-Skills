
# Paper Brigadier Command

## Purpose

Use Paper's Brigadier API to register commands with typed arguments, tab completion and tiered permissions. Built-in `ArgumentTypes` (player selectors, worlds, items, coordinates, ...) are parsed by the server; when a custom argument (for example a `1.5k` / `2m` / `1b` amount) fails to parse, a clear chat error is returned.

Simple commands that need no completion can still stay in `plugin.yml` under `commands:` + `getCommand()`; **the same command must not be declared both ways**.

---

## Platform Requirements

- Paper 1.21.11 / 26.3 (compile-verified on both; runtime-verified on Paper 26.3 and 1.21.11)
- When another plugin runs your command with `Bukkit.dispatchCommand` / `Player#performCommand`, an error thrown while converting a custom argument (`convert`) is not sent to the sender; it reaches the caller wrapped in a `CommandException`. Errors thrown from `executes` are shown normally
- Pure Paper API, no Paperweight needed
- Only difference: `CommandSourceStack#getPlayerOrThrow()` exists on 26.2+ (verified on 26.3); on 1.21.11 use `getExecutor()` to decide
- **Spigot**: Paper only — `io.papermc.paper.command.brigadier` and `LifecycleEvents.COMMANDS` are Paper API; Spigot only has `CommandExecutor`

---

## Generated Code

### Registration (onEnable)

```java
public final class ExamplePlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        PayCommand pay = new PayCommand((from, to, amount) -> PayService.Result.OK);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands registrar = event.registrar();
            registrar.register(pay.build(), "Pay another player", List.of("transfer"));
        });
    }
}
```

### `/pay <player> <amount>`

```java
import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;

LiteralCommandNode<CommandSourceStack> node = Commands.literal("pay")
    .requires(CommandSupport.permission("example.pay"))
    .then(Commands.argument("target", ArgumentTypes.player())
        .then(Commands.argument("amount", new AmountArgumentType())
            .executes(context -> Command.SINGLE_SUCCESS)))
    .build();
```

### Custom k/m/b amount argument

```java
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.OptionalLong;

public final class AmountArgumentType implements CustomArgumentType.Converted<Long, String> {
    @Override
    public Long convert(String nativeType) throws CommandSyntaxException {
        OptionalLong parsed = Amounts.parse(nativeType);   // 1.5k -> 1500
        if (parsed.isEmpty()) {
            throw CommandSupport.error("<red>Invalid amount <input>.", Placeholder.unparsed("input", nativeType));
        }
        return parsed.getAsLong();
    }
    @Override
    public ArgumentType<String> getNativeType() { return StringArgumentType.word(); }
}
```

---

## Rules

- Check permissions with `getSender()`; use `getExecutor()` for actions on oneself
- Return `Command.SINGLE_SUCCESS` on success; on failure `throw` a command exception instead of returning `0` and printing a message yourself
- Always use `Placeholder.unparsed` when player input goes into MiniMessage
- Declare permissions under `permissions:` in `plugin.yml` with an explicit default; do not write `commands:` when using Brigadier
- `paper-plugin.yml` has no `commands:`; for bootstrap registration use `PluginBootstrap#bootstrap` + `LifecycleEvents.COMMANDS`

---

## Thread Safety

- `executes` runs on the main thread; push slow work to async yourself and reply back on the main thread
- `suggests` is not guaranteed to run on the main thread: read only immutable snapshots, do not query the database or iterate mutable collections
