import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping; // Added
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
            OptionMapping userOption = event.getOption("user");
            if (userOption == null) {
                event.reply("❌ User not found.").setEphemeral(true).queue();
                return;
            }
            
            Member target = userOption.getAsMember();
            if (target == null) {
                event.reply("❌ Member not found in this server.").setEphemeral(true).queue();
                return;
            }

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
                event.reply("❌ Error: Minimum 0.01M required to claim.").setEphemeral(true).queue();
                return;
            }
            double amount = ud.rakeback;
            ud.balance += amount;
            ud.rakeback = 0;
            Main.saveUserData(userId, ud);
            event.reply("✅ Claimed " + String.format("%.2f", amount) + "M rakeback!").setEphemeral(true).queue();

        } else if (id.equals("redeem_code")) {
            event.replyModal(Modal.create("modal_redeem", "Redeem Promo Code")
                    .addActionRows(ActionRow.of(TextInput.create("promo_code", "Enter Code", TextInputStyle.SHORT)
                            .setPlaceholder("e.g. FREE10")
                            .setRequired(true)
                            .build())).build()).queue();

        } else if (id.startsWith("admin_")) {
            String[] parts = id.split("_");
            String type = parts[1].substring(0, 1).toUpperCase() + parts[1].substring(1); // Credit or Debit
            String targetId = parts[2];
            
            event.replyModal(Modal.create("modal_admin_" + type + "_" + targetId, "Admin " + type)
                    .addActionRows(ActionRow.of(TextInput.create("amount", "Amount (M)", TextInputStyle.SHORT)
                            .setPlaceholder("Enter amount to " + type.toLowerCase())
                            .setRequired(true)
                            .build())).build()).queue();
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        String modalId = event.getModalId();

        if (modalId.startsWith("modal_admin_")) {
            String[] p = modalId.split("_");
            try {
                String type = p[2];
                String targetId = p[3];
                double amt = Double.parseDouble(event.getValue("amount").getAsString().toLowerCase().replace("m", "").trim());
                
                Main.UserData ud = Main.getUserData(targetId);
                if (type.equalsIgnoreCase("Credit")) { 
                    ud.balance += amt; 
                    ud.totalCredited += amt; 
                } else { 
                    ud.balance -= amt; 
                }
                
                Main.saveUserData(targetId, ud);
                event.reply("✅ Wallet Updated! User: <@" + targetId + ">\nNew Balance: `" + String.format("%.2f", ud.balance) + "M`").setEphemeral(true).queue();
            } catch (Exception e) { 
                event.reply("❌ Error: Please enter a valid numeric amount.").setEphemeral(true).queue(); 
            }

        } else if (modalId.equals("modal_redeem")) {
            String code = event.getValue("promo_code").getAsString().trim();
            Main.UserData ud = Main.getUserData(event.getUser().getId());
            
            if (ud.hasRedeemed) { 
                event.reply("❌ You have already redeemed your welcome bonus!").setEphemeral(true).queue(); 
                return; 
            }
            
            if (code.equalsIgnoreCase("free10")) {
                ud.balance += 10.0; 
                ud.hasRedeemed = true;
                Main.saveUserData(event.getUser().getId(), ud);
                event.reply("✅ Success! 10M Welcome Bonus has been added to your wallet.").setEphemeral(true).queue();
            } else {
                event.reply("❌ Invalid or expired promo code.").setEphemeral(true).queue();
            }
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
