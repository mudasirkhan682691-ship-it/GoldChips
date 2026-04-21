import io.github.cdimascio.dotenv.Dotenv;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.awt.Color;
import java.io.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class Main extends ListenerAdapter {

    private static final String DB_FILE = "database.json";
    public static Map<String, UserData> database = new HashMap<>();
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    public static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    // Rakeback Rate: 6M per 1000M = 0.006
    public static final double RAKEBACK_PERCENTAGE = 0.006;

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.load();
        loadData();
        JDABuilder.createDefault(dotenv.get("DISCORD_BOT_TOKEN"))
                .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_MEMBERS)
                .addEventListeners(new Main(), new Flowerpoker(), new Hotcold())
                .build()
                .updateCommands().addCommands(
                        Commands.slash("wallet", "Check your arcade wallet"),
                        Commands.slash("getwallet", "Admin: Security Check & Management").addOption(OptionType.USER, "user", "Target user", true).setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR)),
                        Commands.slash("fp", "Staff: Host Flower Poker").addOption(OptionType.CHANNEL, "channel", "Select channel", true).setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR)),
                        Commands.slash("hc", "Staff: Host Hot Cold").addOption(OptionType.CHANNEL, "channel", "Select channel", true).setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR))
                ).queue();
    }

    /**
     * Utility method to update wager and rakeback.
     * Call this from Flowerpoker or Hotcold whenever a bet is placed.
     */
    public static void updateWagerAndRakeback(String userId, double betAmount) {
        UserData ud = database.computeIfAbsent(userId, k -> new UserData());
        ud.wagered += betAmount;
        // Calculation: 1000M -> 6M means 0.6% or 0.006 multiplier
        ud.rakeback += (betAmount * RAKEBACK_PERCENTAGE);
        saveData();
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String cmd = event.getName();
        if (cmd.equals("wallet")) {
            UserData data = database.computeIfAbsent(event.getUser().getId(), k -> new UserData());
            event.replyEmbeds(buildCleanEmbed(event.getUser(), data).build()).setEphemeral(true)
                    .addActionRow(Button.secondary("redeem_code", "Redeem Code"), Button.success("claim_rakeback", "Claim Rakeback")).queue();
        } else if (cmd.equals("getwallet")) {
            Member target = event.getOption("user").getAsMember();
            if (target == null) return;
            UserData data = database.computeIfAbsent(target.getId(), k -> new UserData());
            event.replyEmbeds(buildAdminSecurityEmbed(target, data).build()).setEphemeral(true)
                    .addActionRow(Button.primary("admin_credit_" + target.getId(), "Credit"), Button.danger("admin_debit_" + target.getId(), "Debit")).queue();
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (id.startsWith("admin_")) {
            String type = id.contains("credit") ? "Credit" : "Debit";
            event.replyModal(Modal.create("modal_admin_" + type + "_" + id.split("_")[2], type).addActionRows(ActionRow.of(TextInput.create("amount", "Amount", TextInputStyle.SHORT).build())).build()).queue();
        } else if (id.equals("claim_rakeback")) {
            UserData ud = database.get(event.getUser().getId());
            // Minimum claim 0.01M
            if (ud == null || ud.rakeback < 0.01) {
                event.reply("Error: You need at least 0.01M rakeback to claim.").setEphemeral(true).queue();
                return;
            }
            double amount = ud.rakeback;
            ud.balance += amount;
            ud.rakeback = 0;
            saveData();
            event.reply("Successfully claimed " + String.format("%.2f", amount) + "M rakeback!").setEphemeral(true).queue();
        } else if (id.equals("redeem_code")) {
            event.replyModal(Modal.create("modal_redeem", "Redeem Code").addActionRows(ActionRow.of(TextInput.create("promo_code", "Enter Code", TextInputStyle.SHORT).build())).build()).queue();
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        String mId = event.getModalId();
        if (mId.startsWith("modal_admin_")) {
            String[] p = mId.split("_");
            try {
                double amt = Double.parseDouble(event.getValue("amount").getAsString().toLowerCase().replace("m", ""));
                UserData tD = database.computeIfAbsent(p[3], k -> new UserData());
                if (p[2].equals("Credit")) { tD.balance += amt; tD.totalCredited += amt; } else tD.balance -= amt;
                saveData(); event.reply("Wallet Updated!").setEphemeral(true).queue();
            } catch (Exception e) { event.reply("Error! Use numbers only.").setEphemeral(true).queue(); }
        } else if (mId.equals("modal_redeem")) {
            String code = event.getValue("promo_code").getAsString();
            UserData ud = database.computeIfAbsent(event.getUser().getId(), k -> new UserData());
            if (ud.hasRedeemed) { event.reply("Already redeemed!").setEphemeral(true).queue(); return; }
            if (code.equalsIgnoreCase("free10")) {
                ud.balance += 10.0; ud.hasRedeemed = true; saveData();
                event.reply("Success! 10M Welcome Bonus added.").setEphemeral(true).queue();
            } else event.reply("Invalid code.").setEphemeral(true).queue();
        }
    }

    private EmbedBuilder buildCleanEmbed(User u, UserData d) {
        return new EmbedBuilder().setAuthor(u.getName() + "'s Wallet", null, u.getEffectiveAvatarUrl()).setThumbnail(u.getEffectiveAvatarUrl())
                .setColor(new Color(43, 45, 49))
                .addField("💰 Balance", "```" + String.format("%.2fM", d.balance) + "```", true)
                .addField("🎲 Wagered", "```" + String.format("%.2fM", d.wagered) + "```", true)
                .addField("⭐ Rakeback", "```" + String.format("%.2fM", d.rakeback) + "```", true)
                .setFooter("Manage your funds carefully").setTimestamp(Instant.now());
    }

    private EmbedBuilder buildAdminSecurityEmbed(Member m, UserData d) {
        User u = m.getUser();
        long age = ChronoUnit.DAYS.between(u.getTimeCreated(), OffsetDateTime.now());
        return new EmbedBuilder().setAuthor(u.getName(), null, u.getEffectiveAvatarUrl()).setThumbnail(u.getEffectiveAvatarUrl())
                .setColor(age < 7 ? Color.RED : Color.GREEN)
                .addField("💰 Current Balance", "```" + String.format("%.2fM", d.balance) + "```", true)
                .addField("📥 Total Deposited", "```" + String.format("%.2fM", d.totalCredited) + "```", true)
                .addField("🎲 Total Wagered", "```" + String.format("%.2fM", d.wagered) + "```", true)
                .addField("👤 Account Age", age + " days", true)
                .addField("🆔 User ID", "```" + u.getId() + "```", false).setTimestamp(Instant.now());
    }

    public static void saveData() { try (Writer w = new FileWriter(DB_FILE)) { gson.toJson(database, w); } catch (Exception ignored) {} }
    private static void loadData() { File f = new File(DB_FILE); if (f.exists()) { try (Reader r = new FileReader(f)) { database = gson.fromJson(r, new TypeToken<HashMap<String, UserData>>(){}.getType()); } catch (Exception ignored) {} } }

    public static class UserData { public double balance, rakeback, totalCredited, wagered; public boolean hasRedeemed = false; }
}