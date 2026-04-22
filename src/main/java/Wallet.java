import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;

import java.awt.Color;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

public class Wallet extends ListenerAdapter {

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getName().equals("wallet")) {
            Main.UserData data = Main.getUserData(event.getUser().getId());
            event.replyEmbeds(buildUserEmbed(event.getUser(), data).build())
                    .setEphemeral(true)
                    .addActionRow(
                            Button.secondary("redeem_code", "Redeem Code"),
                            Button.success("claim_rakeback", "Claim Rakeback")
                    ).queue();

        } else if (event.getName().equals("getwallet")) {
            Member target = event.getOption("user").getAsMember();
            if (target == null) return;
            Main.UserData data = Main.getUserData(target.getId());
            event.replyEmbeds(buildAdminEmbed(target, data).build())
                    .setEphemeral(true)
                    .addActionRow(
                            Button.primary("admin_credit_" + target.getId(), "Credit"),
                            Button.danger("admin_debit_" + target.getId(), "Debit")
                    ).queue();
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        String userId = event.getUser().getId();

        if (id.equals("claim_rakeback")) {
            Main.UserData ud = Main.getUserData(userId);
            if (ud.rakeback < 0.01) {
                event.reply("❌ Error: Minimum 0.01M required.").setEphemeral(true).queue();
                return;
            }
            double amount = ud.rakeback;
            ud.balance += amount;
            ud.rakeback = 0;
            Main.saveUserData(userId, ud);
            event.reply("✅ Claimed " + String.format("%.2f", amount) + "M rakeback!").setEphemeral(true).queue();

        } else if (id.equals("redeem_code")) {
            event.replyModal(Modal.create("modal_redeem", "Redeem Promo Code")
                    .addActionRows(ActionRow.of(TextInput.create("promo_code", "Enter Code", TextInputStyle.SHORT).build())).build()).queue();

        } else if (id.startsWith("admin_")) {
            String type = id.contains("credit") ? "Credit" : "Debit";
            String targetId = id.split("_")[2];
            event.replyModal(Modal.create("modal_admin_" + type + "_" + targetId, "Admin " + type)
                    .addActionRows(ActionRow.of(TextInput.create("amount", "Amount (M)", TextInputStyle.SHORT).build())).build()).queue();
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        String modalId = event.getModalId();

        if (modalId.startsWith("modal_admin_")) {
            String[] p = modalId.split("_");
            try {
                double amt = Double.parseDouble(event.getValue("amount").getAsString().replace("m", "").trim());
                Main.UserData ud = Main.getUserData(p[3]);
                if (p[2].equals("Credit")) { ud.balance += amt; ud.totalCredited += amt; }
                else { ud.balance -= amt; }
                Main.saveUserData(p[3], ud);
                event.reply("✅ Wallet Updated! New Balance: " + String.format("%.2f", ud.balance) + "M").setEphemeral(true).queue();
            } catch (Exception e) { event.reply("❌ Use numbers only.").setEphemeral(true).queue(); }

        } else if (modalId.equals("modal_redeem")) {
            String code = event.getValue("promo_code").getAsString();
            Main.UserData ud = Main.getUserData(event.getUser().getId());
            if (ud.hasRedeemed) { event.reply("❌ Already redeemed!").setEphemeral(true).queue(); return; }
            if (code.equalsIgnoreCase("free10")) {
                ud.balance += 10.0; ud.hasRedeemed = true;
                Main.saveUserData(event.getUser().getId(), ud);
                event.reply("✅ Success! 10M Welcome Bonus added.").setEphemeral(true).queue();
            } else event.reply("❌ Invalid code.").setEphemeral(true).queue();
        }
    }

    private EmbedBuilder buildUserEmbed(User u, Main.UserData d) {
        return new EmbedBuilder()
                .setAuthor(u.getName() + "'s Wallet", null, u.getEffectiveAvatarUrl())
                .setColor(new Color(43, 45, 49))
                .addField("💰 Balance", "```" + String.format("%.2fM", d.balance) + "```", true)
                .addField("🎲 Wagered", "```" + String.format("%.2fM", d.wagered) + "```", true)
                .addField("⭐ Rakeback", "```" + String.format("%.2fM", d.rakeback) + "```", true)
                .setThumbnail(u.getEffectiveAvatarUrl())
                .setTimestamp(Instant.now());
    }

    private EmbedBuilder buildAdminEmbed(Member m, Main.UserData d) {
        User u = m.getUser();
        long age = ChronoUnit.DAYS.between(u.getTimeCreated(), OffsetDateTime.now());
        return new EmbedBuilder()
                .setAuthor("Admin View: " + u.getName(), null, u.getEffectiveAvatarUrl())
                .setColor(age < 7 ? Color.RED : Color.GREEN)
                .addField("💰 Balance", "```" + String.format("%.2fM", d.balance) + "```", true)
                .addField("📥 Deposited", "```" + String.format("%.2fM", d.totalCredited) + "```", true)
                .addField("🎲 Wagered", "```" + String.format("%.2fM", d.wagered) + "```", true)
                .addField("🆔 User ID", "```" + u.getId() + "```", false)
                .setFooter("Account Age: " + age + " days")
                .setTimestamp(Instant.now());
    }
}
