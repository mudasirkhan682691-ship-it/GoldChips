import io.github.cdimascio.dotenv.Dotenv;
import com.mongodb.client.*;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import com.google.gson.Gson;
import net.dv8tion.jda.api.*;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.*;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.awt.Color;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class Main extends ListenerAdapter {

    private static MongoCollection<Document> userCollection;
    private static final Gson gson = new Gson();
    public static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    public static final double RAKEBACK_PERCENTAGE = 0.006;

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        
        // MongoDB Connection Logic
        String mongoUri = System.getenv("MONGO_URI");
        if (mongoUri == null || mongoUri.isEmpty()) {
            mongoUri = dotenv.get("MONGO_URI");
        }
        
        try {
            MongoClient mongoClient = MongoClients.create(mongoUri);
            MongoDatabase db = mongoClient.getDatabase("arcade_bot");
            userCollection = db.getCollection("users");
            System.out.println("✅ Connected to MongoDB Atlas successfully!");
        } catch (Exception e) {
            System.err.println("❌ MongoDB Connection Failed: " + e.getMessage());
        }

        String token = System.getenv("BOT_TOKEN");
        if (token == null || token.isEmpty()) {
            token = dotenv.get("DISCORD_BOT_TOKEN");
        }

        JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_MEMBERS, GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(new Main(), new Flowerpoker(), new Hotcold())
                .build()
                .updateCommands().addCommands(
                        Commands.slash("wallet", "Check your arcade wallet"),
                        Commands.slash("getwallet", "Admin: Security Check & Management").addOption(OptionType.USER, "user", "Target user", true).setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR)),
                        Commands.slash("fp", "Staff: Host Flower Poker").addOption(OptionType.CHANNEL, "channel", "Select channel", true).setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR)),
                        Commands.slash("hc", "Staff: Host Hot Cold").addOption(OptionType.CHANNEL, "channel", "Select channel", true).setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR))
                ).queue();
        
        System.out.println("🚀 Bot is starting with MongoDB support...");
    }

    // --- Database Helper Methods ---

    public static UserData getUserData(String userId) {
        Document doc = userCollection.find(new Document("_id", userId)).first();
        if (doc == null) return new UserData();
        return gson.fromJson(doc.toJson(), UserData.class);
    }

    public static void saveUserData(String userId, UserData data) {
        Document doc = Document.parse(gson.toJson(data));
        doc.put("_id", userId); 
        userCollection.replaceOne(new Document("_id", userId), doc, new ReplaceOptions().upsert(true));
    }

    public static void updateWagerAndRakeback(String userId, double betAmount) {
        UserData ud = getUserData(userId);
        ud.wagered += betAmount;
        ud.rakeback += (betAmount * RAKEBACK_PERCENTAGE);
        saveUserData(userId, ud);
    }

    // --- Interaction Listeners ---

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getName().equals("wallet")) {
            UserData data = getUserData(event.getUser().getId());
            event.replyEmbeds(buildCleanEmbed(event.getUser(), data).build()).setEphemeral(true)
                    .addActionRow(Button.secondary("redeem_code", "Redeem Code"), Button.success("claim_rakeback", "Claim Rakeback")).queue();
        } else if (event.getName().equals("getwallet")) {
            Member target = event.getOption("user").getAsMember();
            if (target == null) return;
            UserData data = getUserData(target.getId());
            event.replyEmbeds(buildAdminSecurityEmbed(target, data).build()).setEphemeral(true)
                    .addActionRow(Button.primary("admin_credit_" + target.getId(), "Credit"), Button.danger("admin_debit_" + target.getId(), "Debit")).queue();
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (id.startsWith("admin_")) {
            String type = id.contains("credit") ? "Credit" : "Debit";
            event.replyModal(Modal.create("modal_admin_" + type + "_" + id.split("_")[2], type)
                    .addActionRows(ActionRow.of(TextInput.create("amount", "Amount", TextInputStyle.SHORT).build())).build()).queue();
        } else if (id.equals("claim_rakeback")) {
            UserData ud = getUserData(event.getUser().getId());
            if (ud.rakeback < 0.01) {
                event.reply("Error: You need at least 0.01M rakeback to claim.").setEphemeral(true).queue();
                return;
            }
            double amount = ud.rakeback;
            ud.balance += amount;
            ud.rakeback = 0;
            saveUserData(event.getUser().getId(), ud);
            event.reply("Successfully claimed " + String.format("%.2f", amount) + "M rakeback!").setEphemeral(true).queue();
        } else if (id.equals("redeem_code")) {
            event.replyModal(Modal.create("modal_redeem", "Redeem Code")
                    .addActionRows(ActionRow.of(TextInput.create("promo_code", "Enter Code", TextInputStyle.SHORT).build())).build()).queue();
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (event.getModalId().startsWith("modal_admin_")) {
            String[] p = event.getModalId().split("_");
            try {
                double amt = Double.parseDouble(event.getValue("amount").getAsString().toLowerCase().replace("m", ""));
                UserData ud = getUserData(p[3]);
                if (p[2].equals("Credit")) { ud.balance += amt; ud.totalCredited += amt; } else ud.balance -= amt;
                saveUserData(p[3], ud);
                event.reply("Wallet Updated!").setEphemeral(true).queue();
            } catch (Exception e) { event.reply("Error! Use numbers only.").setEphemeral(true).queue(); }
        } else if (event.getModalId().equals("modal_redeem")) {
            String code = event.getValue("promo_code").getAsString();
            UserData ud = getUserData(event.getUser().getId());
            if (ud.hasRedeemed) { event.reply("Already redeemed!").setEphemeral(true).queue(); return; }
            if (code.equalsIgnoreCase("free10")) {
                ud.balance += 10.0; ud.hasRedeemed = true;
                saveUserData(event.getUser().getId(), ud);
                event.reply("Success! 10M Welcome Bonus added.").setEphemeral(true).queue();
            } else event.reply("Invalid code.").setEphemeral(true).queue();
        }
    }

    // --- Old UI Embed Builders ---

    private EmbedBuilder buildCleanEmbed(User u, UserData d) {
        return new EmbedBuilder().setAuthor(u.getName() + "'s Wallet", null, u.getEffectiveAvatarUrl())
                .setThumbnail(u.getEffectiveAvatarUrl())
                .setColor(new Color(43, 45, 49))
                .addField("💰 Balance", "```" + String.format("%.2fM", d.balance) + "```", true)
                .addField("🎲 Wagered", "```" + String.format("%.2fM", d.wagered) + "```", true)
                .addField("⭐ Rakeback", "```" + String.format("%.2fM", d.rakeback) + "```", true)
                .setFooter("Manage your funds carefully").setTimestamp(Instant.now());
    }

    private EmbedBuilder buildAdminSecurityEmbed(Member m, UserData d) {
        User u = m.getUser();
        long age = ChronoUnit.DAYS.between(u.getTimeCreated(), OffsetDateTime.now());
        return new EmbedBuilder().setAuthor(u.getName(), null, u.getEffectiveAvatarUrl())
                .setThumbnail(u.getEffectiveAvatarUrl())
                .setColor(age < 7 ? Color.RED : Color.GREEN)
                .addField("💰 Current Balance", "```" + String.format("%.2fM", d.balance) + "```", true)
                .addField("📥 Total Deposited", "```" + String.format("%.2fM", d.totalCredited) + "```", true)
                .addField("🎲 Total Wagered", "```" + String.format("%.2fM", d.wagered) + "```", true)
                .addField("👤 Account Age", age + " days", true)
                .addField("🆔 User ID", "```" + u.getId() + "```", false)
                .setTimestamp(Instant.now());
    }

    public static class UserData { public double balance, rakeback, totalCredited, wagered; public boolean hasRedeemed = false; }
}
