import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
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

public class Cashier extends ListenerAdapter {

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getName().equals("cashier")) {
            MessageChannel channel = event.getOption("channel").getAsChannel().asGuildMessageChannel();
            
            EmbedBuilder eb = new EmbedBuilder()
                    .setTitle("🏦 Global Arcade Cashier")
                    .setDescription("Welcome to the automated cashier system.\n\n" +
                            "🔹 **Deposit:** Add chips to your wallet.\n" +
                            "🔹 **Withdraw:** Remove chips from your wallet.\n\n" +
                            "Click the buttons below to manage your funds.")
                    .setColor(new Color(255, 215, 0)) // Gold Color
                    .setThumbnail(event.getGuild().getIconUrl())
                    .setFooter("Secure Transactions Powered by MongoDB")
                    .setTimestamp(Instant.now());

            channel.sendMessageEmbeds(eb.build())
                    .addActionRow(
                            Button.success("btn_deposit", "Deposit Chips 📥"),
                            Button.danger("btn_withdraw", "Withdraw Chips 📤")
                    ).queue();

            event.reply("✅ Cashier desk has been setup in " + channel.getAsMention()).setEphemeral(true).queue();
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (event.getComponentId().equals("btn_deposit")) {
            TextInput amount = TextInput.create("dep_amount", "Deposit Amount (M)", TextInputStyle.SHORT)
                    .setPlaceholder("Example: 50")
                    .setRequired(true)
                    .build();
            
            event.replyModal(Modal.create("modal_deposit", "Deposit Chips")
                    .addActionRows(ActionRow.of(amount)).build()).queue();

        } else if (event.getComponentId().equals("btn_withdraw")) {
            TextInput amount = TextInput.create("wd_amount", "Withdraw Amount (M)", TextInputStyle.SHORT)
                    .setPlaceholder("Example: 25")
                    .setRequired(true)
                    .build();

            event.replyModal(Modal.create("modal_withdraw", "Withdraw Chips")
                    .addActionRows(ActionRow.of(amount)).build()).queue();
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        String userId = event.getUser().getId();
        Main.UserData ud = Main.getUserData(userId);

        if (event.getModalId().equals("modal_deposit")) {
            try {
                double amount = parseAmount(event.getValue("dep_amount").getAsString());
                ud.balance += amount;
                ud.totalCredited += amount;
                Main.saveUserData(userId, ud);

                event.reply("✅ **Deposit Successful!** Added " + amount + "M to your wallet.").setEphemeral(true).queue();
            } catch (Exception e) {
                event.reply("❌ Error! Please enter a valid number.").setEphemeral(true).queue();
            }

        } else if (event.getModalId().equals("modal_withdraw")) {
            try {
                double amount = parseAmount(event.getValue("wd_amount").getAsString());

                if (ud.balance < amount) {
                    event.reply("❌ **Insufficient Balance!** You only have " + ud.balance + "M").setEphemeral(true).queue();
                    return;
                }

                ud.balance -= amount;
                Main.saveUserData(userId, ud);

                event.reply("✅ **Withdrawal Successful!** Removed " + amount + "M from your wallet.").setEphemeral(true).queue();
            } catch (Exception e) {
                event.reply("❌ Error! Please enter a valid number.").setEphemeral(true).queue();
            }
        }
    }

    private double parseAmount(String input) {
        return Double.parseDouble(input.toLowerCase().replace("m", "").trim());
    }
}
