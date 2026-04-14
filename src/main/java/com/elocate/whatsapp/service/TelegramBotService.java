package com.elocate.whatsapp.service;

import com.elocate.whatsapp.model.Driver;
import com.elocate.whatsapp.model.RecycleRequest;
import com.elocate.whatsapp.model.WhatsAppSession;
import com.elocate.whatsapp.model.enums.FulfillmentStatus;
import com.elocate.whatsapp.repository.DriverRepository;
import com.elocate.whatsapp.repository.RecycleRequestRepository;
import com.elocate.whatsapp.repository.WhatsAppSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Telegram bot state machine — mirrors WhatsAppBotService exactly.
 * Session key = Telegram chat ID (stored as string in WhatsAppSession.phoneNumber).
 * Prefix "tg:" is added to avoid collision with WhatsApp phone numbers.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TelegramBotService {

    private static final int PAGE_SIZE = 5;
    private static final String TG_PREFIX = "tg:";

    private final WhatsAppSessionRepository sessionRepo;
    private final DriverRepository driverRepo;
    private final RecycleRequestRepository requestRepo;
    private final OtpService otpService;
    private final TelegramApiClient apiClient;
    private final ElocateServerClient elocateClient;

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    @Transactional
    public void handleMessage(Long chatId, String text, String callbackData) {
        String sessionKey = TG_PREFIX + chatId;

        WhatsAppSession session = sessionRepo.findById(sessionKey).orElseGet(() -> {
            WhatsAppSession s = new WhatsAppSession();
            s.setPhoneNumber(sessionKey);
            s.setState("AWAITING_EMAIL");
            return s;
        });

        String input = (callbackData != null) ? callbackData : (text != null ? text.trim() : "");

        switch (session.getState()) {
            case "AWAITING_EMAIL"         -> handleEmail(session, chatId, input);
            case "AWAITING_OTP"           -> handleOtp(session, chatId, input);
            case "MAIN_MENU"              -> handleMainMenu(session, chatId, input);
            case "PENDING_LIST"           -> handleListNav(session, chatId, input, false);
            case "COMPLETED_LIST"         -> handleListNav(session, chatId, input, true);
            case "PROFILE"               -> handleProfile(session, chatId, input);
            case "AWAITING_REJECT_REASON" -> handleRejectReason(session, chatId, input);
            default                       -> resetToEmail(session, chatId);
        }

        sessionRepo.save(session);
    }

    // -------------------------------------------------------------------------
    // State handlers
    // -------------------------------------------------------------------------

    private void handleEmail(WhatsAppSession session, Long chatId, String email) {
        Optional<Driver> driverOpt = driverRepo.findByEmail(email.toLowerCase());
        if (driverOpt.isEmpty()) {
            apiClient.sendText(chatId,
                    "❌ That email is not registered as a driver in ELocate.\n\n" +
                    "Please check and try again, or contact your admin.");
            return;
        }

        Driver driver = driverOpt.get();
        session.setPendingEmail(driver.getEmail());
        session.setState("AWAITING_OTP");
        otpService.generateAndSend(session, driver.getEmail());

        apiClient.sendText(chatId,
                "✅ Found your account! An OTP has been sent to *" + maskEmail(driver.getEmail()) + "*.\n\n" +
                "Please enter the 6-digit OTP to continue.");
    }

    private void handleOtp(WhatsAppSession session, Long chatId, String input) {
        if (!otpService.verify(session, input)) {
            apiClient.sendText(chatId, "❌ Invalid or expired OTP. Please try again.\n\nType your OTP:");
            return;
        }

        Driver driver = driverRepo.findByEmail(session.getPendingEmail()).orElseThrow();
        session.setDriverId(driver.getId());
        session.setState("MAIN_MENU");
        otpService.clear(session);

        apiClient.sendText(chatId, "✅ Verified! Welcome, *" + driver.getName() + "* 👋");
        sendMainMenu(chatId);
    }

    private void handleMainMenu(WhatsAppSession session, Long chatId, String input) {
        switch (input) {
            case "menu_pending" -> {
                session.setState("PENDING_LIST");
                session.setListOffset(0);
                sendPickupList(session, chatId, false);
            }
            case "menu_completed" -> {
                session.setState("COMPLETED_LIST");
                session.setListOffset(0);
                sendPickupList(session, chatId, true);
            }
            case "menu_profile" -> {
                session.setState("PROFILE");
                sendProfile(session, chatId);
            }
            default -> sendMainMenu(chatId);
        }
    }

    private void handleListNav(WhatsAppSession session, Long chatId, String input, boolean completed) {
        if (input.startsWith("accept_pickup:") || input.startsWith("reject_pickup:") || input.startsWith("start_pickup:")) {
            handlePickupAction(session, chatId, input);
            return;
        }
        if (input.equals("menu_pending") || input.equals("menu_completed") || input.equals("menu_profile")) {
            session.setState("MAIN_MENU");
            handleMainMenu(session, chatId, input);
            return;
        }
        switch (input) {
            case "list_next" -> {
                session.setListOffset(session.getListOffset() + PAGE_SIZE);
                sendPickupList(session, chatId, completed);
            }
            case "list_back" -> {
                session.setState("MAIN_MENU");
                sendMainMenu(chatId);
            }
            default -> sendPickupList(session, chatId, completed);
        }
    }

    private void handleProfile(WhatsAppSession session, Long chatId, String input) {
        if ("profile_back".equals(input)) {
            session.setState("MAIN_MENU");
            sendMainMenu(chatId);
        } else {
            sendProfile(session, chatId);
        }
    }

    private void handlePickupAction(WhatsAppSession session, Long chatId, String input) {
        String[] parts = input.split(":", 2);
        String action = parts[0];
        String requestNumber = parts.length > 1 ? parts[1] : "unknown";

        switch (action) {
            case "accept_pickup" -> {
                String token = elocateClient.resolveToken(requestNumber, "ACCEPT");
                if (token == null) {
                    apiClient.sendText(chatId,
                            "⚠️ Could not process accept for *" + requestNumber + "*.\n" +
                            "The action link may have expired. Please contact your intermediary.");
                    return;
                }
                boolean ok = elocateClient.acceptPickup(token);
                session.setState("MAIN_MENU");
                if (ok) {
                    apiClient.sendButtons(chatId,
                            "✅ Request *" + requestNumber + "* marked as *Completed*.\nThe citizen has been notified. 🎉",
                            List.of(
                                    Map.of("text", "⏳ View Pending", "callback_data", "menu_pending"),
                                    Map.of("text", "🏠 Main Menu",   "callback_data", "list_back")
                            ));
                } else {
                    apiClient.sendText(chatId, "⚠️ Something went wrong for *" + requestNumber + "*. Please try again.");
                }
            }
            case "start_pickup" -> {
                String token = elocateClient.resolveToken(requestNumber, "IN_PROGRESS");
                if (token == null) {
                    apiClient.sendText(chatId, "⚠️ Could not mark *" + requestNumber + "* as In Progress.");
                    return;
                }
                boolean ok = elocateClient.markInProgress(token);
                session.setState("MAIN_MENU");
                if (ok) {
                    apiClient.sendButtons(chatId,
                            "🚗 Request *" + requestNumber + "* marked as *In Progress*.\nSafe driving!",
                            List.of(
                                    Map.of("text", "⏳ View Pending", "callback_data", "menu_pending"),
                                    Map.of("text", "🏠 Main Menu",   "callback_data", "list_back")
                            ));
                } else {
                    apiClient.sendText(chatId, "⚠️ Something went wrong for *" + requestNumber + "*. Please try again.");
                }
            }
            case "reject_pickup" -> {
                session.setState("AWAITING_REJECT_REASON");
                session.setPendingActionRequest(requestNumber);
                apiClient.sendText(chatId,
                        "❌ You are rejecting request *" + requestNumber + "*.\n\nPlease type a brief reason:");
            }
        }
    }

    private void handleRejectReason(WhatsAppSession session, Long chatId, String reason) {
        String requestNumber = session.getPendingActionRequest();
        String token = elocateClient.resolveToken(requestNumber, "REJECT");

        session.setState("MAIN_MENU");
        session.setPendingActionRequest(null);

        if (token == null) {
            apiClient.sendText(chatId, "⚠️ Could not process rejection for *" + requestNumber + "*. Link may have expired.");
            return;
        }

        boolean ok = elocateClient.rejectPickup(token, reason);
        if (ok) {
            apiClient.sendButtons(chatId,
                    "❌ Request *" + requestNumber + "* marked as *Rejected*.\nReason: _" + reason + "_\nThe citizen has been notified.",
                    List.of(
                            Map.of("text", "⏳ View Pending", "callback_data", "menu_pending"),
                            Map.of("text", "🏠 Main Menu",   "callback_data", "list_back")
                    ));
        } else {
            apiClient.sendText(chatId, "⚠️ Something went wrong for *" + requestNumber + "*. Please try again.");
        }
    }

    // -------------------------------------------------------------------------
    // Message builders
    // -------------------------------------------------------------------------

    private void sendMainMenu(Long chatId) {
        apiClient.sendButtons(chatId,
                "🏠 *Main Menu*\n\nWhat would you like to check?",
                List.of(
                        Map.of("text", "⏳ Pending Pickups",  "callback_data", "menu_pending"),
                        Map.of("text", "✅ Completed",         "callback_data", "menu_completed"),
                        Map.of("text", "👤 My Profile",        "callback_data", "menu_profile")
                ));
    }

    private void sendPickupList(WhatsAppSession session, Long chatId, boolean completed) {
        PageRequest pageable = PageRequest.of(
                session.getListOffset() / PAGE_SIZE, PAGE_SIZE,
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<RecycleRequest> page;
        String title;

        if (completed) {
            page = requestRepo.findByAssignedDriverIdAndFulfillmentStatus(
                    session.getDriverId(), FulfillmentStatus.PICKUP_COMPLETED, pageable);
            title = "✅ *Completed Pickups*";
        } else {
            page = requestRepo.findByAssignedDriverIdAndFulfillmentStatusIn(
                    session.getDriverId(),
                    List.of(FulfillmentStatus.PICKUP_ASSIGNED, FulfillmentStatus.PICKUP_IN_PROGRESS),
                    pageable);
            title = "⏳ *Pending Pickups*";
        }

        if (page.isEmpty() && session.getListOffset() == 0) {
            apiClient.sendButtons(chatId, title + "\n\nNo records found.",
                    List.of(Map.of("text", "⬅ Back to Menu", "callback_data", "list_back")));
            return;
        }

        StringBuilder sb = new StringBuilder(title).append("\n\n");
        int index = session.getListOffset() + 1;
        for (RecycleRequest r : page.getContent()) {
            sb.append("*").append(index++).append(". ").append(r.getRequestNumber()).append("*\n");
            if (r.getDeviceModel() != null) sb.append("📱 ").append(r.getDeviceModel().getModelName()).append("\n");
            if (r.getPickupAddress() != null)
                sb.append("📍 ").append(r.getPickupAddress().getAddress()).append(", ").append(r.getPickupAddress().getCity()).append("\n");
            if (r.getPickupDate() != null) sb.append("📅 ").append(r.getPickupDate()).append("\n");
            sb.append("Status: ").append(r.getFulfillmentStatus()).append("\n\n");
        }

        List<Map<String, String>> buttons = page.hasNext()
                ? List.of(
                        Map.of("text", "Next 5 ➡", "callback_data", "list_next"),
                        Map.of("text", "⬅ Back to Menu", "callback_data", "list_back"))
                : List.of(Map.of("text", "⬅ Back to Menu", "callback_data", "list_back"));

        apiClient.sendButtons(chatId, sb.toString(), buttons);
    }

    private void sendProfile(WhatsAppSession session, Long chatId) {
        Driver driver = driverRepo.findById(session.getDriverId()).orElse(null);
        if (driver == null) { resetToEmail(session, chatId); return; }

        String msg = "👤 *Your Profile*\n\n" +
                "Name: " + driver.getName() + "\n" +
                "Phone: " + driver.getPhone() + "\n" +
                "Vehicle: " + driver.getVehicleNumber() + " (" + driver.getVehicleType() + ")\n" +
                "Status: " + driver.getAvailability();

        apiClient.sendButtons(chatId, msg,
                List.of(Map.of("text", "⬅ Back to Menu", "callback_data", "profile_back")));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void resetToEmail(WhatsAppSession session, Long chatId) {
        session.setState("AWAITING_EMAIL");
        session.setDriverId(null);
        apiClient.sendText(chatId,
                "👋 Welcome to *ELocate Driver Bot*!\n\nPlease enter your registered email address to get started.");
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 2) return email;
        return email.charAt(0) + "***" + email.substring(at);
    }
}
