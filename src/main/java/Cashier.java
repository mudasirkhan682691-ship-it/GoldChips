import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
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
import java.util.EnumSet;

public class Cashier extends ListenerAdapter {

    // Aapki di hui Category ID
    private static final String CATEGORY_ID = "1496368855985950842"; 
    private static final double MINIMUM_LIMIT = 10.0;

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getName().equals("cashier")) {
            MessageChannel channel = event.getOption("channel").getAsChannel().asGuildMessageChannel();
            
            EmbedBuilder eb = new EmbedBuilder()
                    .setTitle("🏦 Global Arcade | Central Cashier")
                    .setDescription("Welcome to the official cashier desk. Manage your chips securely via our ticket system.\n\n" +
                            "💵 **Deposit:** Click below to open a ticket for adding chips.\n" +
                            "💳 **Withdraw:** Click below to open a ticket for cashing out.\n\n" +
                            "⚠️ **Rules:**\n" +
                            "* Minimum Transaction: **10M**\n" +
                            "* Withdrawals require a 10M+ wallet balance.\n" +
                            "━━━━━━━━━━━━━━━━━━━━━")
                    .setColor(new Color(255, 215, 0)) // Gold UI
                    .setThumbnail(event.getGuild().getIconUrl())
                    .setFooter("Reliable & Secure Transactions")
                    .setTimestamp(Instant.now());

            channel.sendMessageEmbeds(eb.build())
                    .addActionRow(
                            Button.success("btn_deposit", "Deposit Chips 📥"),
                            Button.danger("btn_withdraw", "Withdraw Chips 📤")
                    ).queue();

            event.reply("✅ Cashier desk setup completed in " + channel.getAsMention()).setEphemeral(true).queue();
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String memberId = event.getUser().getId();
        Main.UserData ud = Main.getUserData(memberId);

        if (event.getComponentId().equals("btn_deposit")) {
            TextInput amount = TextInput.create("dep_amount", "Deposit Amount (M)", TextInputStyle.SHORT)
                    .setPlaceholder("Min: 10")
                    .setRequired(true)
                    .build();
            
            event.replyModal(Modal.create("modal_deposit", "Deposit Request")
                    .addActionRows(ActionRow.of(amount)).build()).queue();

        } else if (event.getComponentId().equals("btn_withdraw")) {
            // Withdraw check: Wallet mein 10M se zyada hona lazmi hai
            if (ud.balance < MINIMUM_LIMIT) {
                event.reply("❌ **Access Denied!** You need at least **10M** in your wallet to request a withdrawal. \nYour current balance: `" + String.format("%.2f", ud.balance) + "M`")
                        .setEphemeral(true).queue();
                return;
            }

            TextInput amount = TextInput.create("wd_amount", "Withdraw Amount (M)", TextInputStyle.SHORT)
                    .setPlaceholder("Min: 10")
                    .setRequired(true)
                    .build();

            event.replyModal(Modal.create("modal_withdraw", "Withdrawal Request")
                    .addActionRows(ActionRow.of(amount)).build()).queue();
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        String userId = event.getUser().getId();
        Category category = event.getGuild().getCategoryById(CATEGORY_ID);

        if (category == null) {
            event.reply("❌ Error: Ticket category not found! Please check the ID.").setEphemeral(true).queue();
            return;
        }

        if (event.getModalId().equals("modal_deposit")) {
            try {
                double amount = parseAmount(event.getValue("dep_amount").getAsString());
                if (amount < MINIMUM_LIMIT) {
                    event.reply("❌ Minimum deposit amount is **10M**.").setEphemeral(true).queue();
                    return;
                }
                createTicket(event, "deposit", amount, category);
            } catch (Exception e) {
                event.reply("❌ Invalid number! Please enter only digits.").setEphemeral(true).queue();
            }

        } else if (event.getModalId().equals("modal_withdraw")) {
            try {
                double amount = parseAmount(event.getValue("wd_amount").getAsString());
                Main.UserData ud = Main.getUserData(userId);

                if (amount < MINIMUM_LIMIT) {
                    event.reply("❌ Minimum withdrawal amount is **10M**.").setEphemeral(true).queue();
                    return;
                }
                if (ud.balance < amount) {
                    event.reply("❌ Insufficient funds! You requested " + amount + "M but only have " + ud.balance + "M.").setEphemeral(true).queue();
                    return;
                }
                createTicket(event, "withdraw", amount, category);
            } catch (Exception e) {
                event.reply("❌ Invalid number! Please enter only digits.").setEphemeral(true).queue();
            }
        }
    }

    private void createTicket(ModalInteractionEvent event, String type, double amount, Category category) {
        String typeLabel = type.equals("deposit") ? "DEP" : "WD";
        String ticketName = typeLabel + "-" + event.getUser().getName();

        category.createTextChannel(ticketName)
                .addPermissionOverride(event.getGuild().getPublicRole(), null, EnumSet.of(Permission.VIEW_CHANNEL))
                .addPermissionOverride(event.getMember(), EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND), null)
                .queue(channel -> {
                    EmbedBuilder eb = new EmbedBuilder()
                            .setTitle("Ticket: " + (type.equals("deposit") ? "Deposit Request" : "Withdrawal Request"))
                            .setAuthor(event.getUser().getName(), null, event.getUser().getEffectiveAvatarUrl())
                            .setDescription("A new request has been submitted. Staff will be with you shortly.\n\n" +
                                    "💳 **Transaction Type:** " + type.toUpperCase() + "\n" +
                                    "💰 **Amount Requested:** `" + amount + "M`\n" +
                                    "👤 **User ID:** `" + event.getUser().getId() + "`\n" +
                                    "━━━━━━━━━━━━━━━━━━━━━")
                            .setColor(type.equals("deposit") ? Color.CYAN : Color.ORANGE)
                            .setFooter("Global Arcade | Internal Ticket System")
                            .setTimestamp(Instant.now());

                    channel.sendMessage(event.getMember().getAsMention() + " @here").setEmbeds(eb.build())
                            .addActionRow(Button.danger("close_ticket", "Close Ticket 🔒")).queue();

                    event.reply("✅ Ticket successfully created! Access here: " + channel.getAsMention()).setEphemeral(true).queue();
                });
    }

    private double parseAmount(String input) {
        return Double.parseDouble(input.toLowerCase().replace("m", "").trim());
    }
}
