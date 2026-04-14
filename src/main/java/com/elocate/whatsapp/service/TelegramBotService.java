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

        // /start always resets — prevents stuck sessions
        if ("/start".equals(input)) {
            resetToEmail(session, chatId);
            sessionRepo.save(session);
            return;
        }

        switch (session.getState()) {
            case "AWAITING_EMAIL"          -> handleEmail(session, chatId, input);
            case "AWAITING_OTP"            -> handleOtp(session, chatId, input);
            case "MAIN_MENU"               -> handleMainMenu(session, chatId, input);
            case "PENDING_LIST"            -> handleListNav(session, chatId, input, false);
            case "COMPLETED_LIST"          -> handleListNav(session, chatId, input, true);
            case "PROFILE"                -> handleProfile(session, chatId, input);
            case "AWAITING_REQUEST_ID"     -> {
                // Button taps while in request ID state (process actions or back)
                if (input.equals("process_accept") || input.equals("process_reject") || input.equals("process_inprogress")) {
                    handleProcessAction(session, chatId, input);
                } else if (input.equals("list_back") || input.equals("menu_back")) {
                    session.setState("MAIN_MENU");
                    sendMainMenu(chatId);
                } else {
                    handleRequestId(session, chatId, input);
                }
            }
            case "AWAITING_ACCEPT_REMARKS" -> handleAcceptRemarks(session, chatId, input);
            case "AWAITING_REJECT_REASON"  -> handleRejectReason(session, chatId, input);
            default                        -> resetToEmail(session, chatId);
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
                "✅ Found your account! An OTP has been sent to <b>" + maskEmail(driver.getEmail()) + "</b>.\n\n" +
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

        apiClient.sendText(chatId, "✅ Verified! Welcome, <b>" + driver.getName() + "</b> 👋");
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
            case "menu_process" -> {
                session.setState("AWAITING_REQUEST_ID");
                apiClient.sendText(chatId,
                        "⚙️ <b>Process a Request</b>\n\nEnter the request ID (e.g. <code>RCY-2026-000042</code>):");
            }
            default -> sendMainMenu(chatId);
        }
    }

    private void handleListNav(WhatsAppSession session, Long chatId, String input, boolean completed) {
        // Pickup action buttons from proactive notifications
        if (input.startsWith("accept_pickup:") || input.startsWith("reject_pickup:") || input.startsWith("start_pickup:")) {
            handlePickupAction(session, chatId, input);
            return;
        }
        // Process request action buttons
        if (input.equals("process_accept") || input.equals("process_reject") || input.equals("process_inprogress")) {
            handleProcessAction(session, chatId, input);
            return;
        }
        // Main menu buttons from any state
        if (input.equals("menu_pending") || input.equals("menu_completed") || input.equals("menu_profile") || input.equals("menu_process")) {
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

    // ── Process Request flow ──────────────────────────────────────────────────

    private void handleRequestId(WhatsAppSession session, Long chatId, String requestNumber) {
        // Allow cancel
        if ("cancel".equalsIgnoreCase(requestNumber) || "menu_back".equals(requestNumber)) {
            session.setState("MAIN_MENU");
            sendMainMenu(chatId);
            return;
        }

        RecycleRequest request = requestRepo.findByRequestNumber(requestNumber.trim().toUpperCase()).orElse(null);

        if (request == null) {
            apiClient.sendButtons(chatId,
                    "❌ Request <b>" + requestNumber + "</b> not found.\n\nPlease check the ID and try again.",
                    List.of(
                            Map.of("text", "🔄 Try Again",   "callback_data", "menu_process"),
                            Map.of("text", "⬅ Back to Menu", "callback_data", "list_back")
                    ));
            session.setState("MAIN_MENU");
            return;
        }

        // Verify this request belongs to this driver
        if (!session.getDriverId().equals(request.getAssignedDriverId())) {
            apiClient.sendButtons(chatId,
                    "❌ Request <b>" + requestNumber + "</b> is not assigned to you.",
                    List.of(Map.of("text", "⬅ Back to Menu", "callback_data", "list_back")));
            session.setState("MAIN_MENU");
            return;
        }

        FulfillmentStatus status = request.getFulfillmentStatus();

        if (status == FulfillmentStatus.PICKUP_COMPLETED) {
            apiClient.sendButtons(chatId,
                    "✅ Request <b>" + requestNumber + "</b> is already <b>Completed</b>.",
                    List.of(Map.of("text", "⬅ Back to Menu", "callback_data", "list_back")));
            session.setState("MAIN_MENU");
            return;
        }

        if (status == FulfillmentStatus.PICKUP_FAILED) {
            apiClient.sendButtons(chatId,
                    "❌ Request <b>" + requestNumber + "</b> is already marked as <b>Failed/Rejected</b>.",
                    List.of(Map.of("text", "⬅ Back to Menu", "callback_data", "list_back")));
            session.setState("MAIN_MENU");
            return;
        }

        if (status == FulfillmentStatus.PICKUP_ASSIGNED || status == FulfillmentStatus.PICKUP_IN_PROGRESS) {
            session.setPendingActionRequest(requestNumber.trim().toUpperCase());
            // Stay in AWAITING_REQUEST_ID — next input will be a button tap
            apiClient.sendButtons(chatId,
                    "⚙️ <b>Request " + requestNumber + "</b>\n" +
                    "Device: " + (request.getDeviceModel() != null ? request.getDeviceModel().getModelName() : "N/A") + "\n" +
                    "Status: " + status.name() + "\n\n" +
                    "What would you like to do?",
                    List.of(
                            Map.of("text", "✅ Accept",       "callback_data", "process_accept"),
                            Map.of("text", "❌ Reject",       "callback_data", "process_reject"),
                            Map.of("text", "🚗 In Progress",  "callback_data", "process_inprogress"),
                            Map.of("text", "⬅ Back to Menu", "callback_data", "list_back")
                    ));
            return;
        }

        apiClient.sendButtons(chatId,
                "ℹ️ Request <b>" + requestNumber + "</b> is in status <b>" + status.name() + "</b> and cannot be processed.",
                List.of(Map.of("text", "⬅ Back to Menu", "callback_data", "list_back")));
        session.setState("MAIN_MENU");
    }

    private void handleAcceptRemarks(WhatsAppSession session, Long chatId, String remarks) {
        String requestNumber = session.getPendingActionRequest();
        String token = elocateClient.resolveToken(requestNumber, "ACCEPT");

        session.setState("MAIN_MENU");
        session.setPendingActionRequest(null);
        session.setPendingAction(null);

        if (token == null) {
            apiClient.sendText(chatId,
                    "⚠️ Could not process accept for <b>" + requestNumber + "</b>. Token expired or not found.");
            return;
        }

        // Pass remarks as comments
        boolean ok = elocateClient.acceptPickupWithRemarks(token, remarks);
        if (ok) {
            apiClient.sendButtons(chatId,
                    "✅ Request <b>" + requestNumber + "</b> marked as <b>Completed</b>.\n" +
                    "Remarks saved: <i>" + remarks + "</i>\nThe citizen has been notified. 🎉",
                    List.of(
                            Map.of("text", "⏳ View Pending", "callback_data", "menu_pending"),
                            Map.of("text", "🏠 Main Menu",   "callback_data", "list_back")
                    ));
        } else {
            apiClient.sendText(chatId, "⚠️ Something went wrong for <b>" + requestNumber + "</b>. Please try again.");
        }
    }

    private void handleProcessAction(WhatsAppSession session, Long chatId, String input) {
        String requestNumber = session.getPendingActionRequest();
        if (requestNumber == null) {
            session.setState("MAIN_MENU");
            sendMainMenu(chatId);
            return;
        }
        switch (input) {
            case "process_accept" -> {
                session.setState("AWAITING_ACCEPT_REMARKS");
                session.setPendingAction("ACCEPT");
                apiClient.sendText(chatId,
                        "✅ Accepting request <b>" + requestNumber + "</b>.\n\nPlease enter your remarks for this pickup:");
            }
            case "process_reject" -> {
                session.setState("AWAITING_REJECT_REASON");
                session.setPendingAction("REJECT");
                apiClient.sendText(chatId,
                        "❌ Rejecting request <b>" + requestNumber + "</b>.\n\nPlease type a reason for rejection:");
            }
            case "process_inprogress" -> {
                String token = elocateClient.resolveToken(requestNumber, "IN_PROGRESS");
                session.setState("MAIN_MENU");
                session.setPendingActionRequest(null);
                if (token != null && elocateClient.markInProgress(token)) {
                    apiClient.sendButtons(chatId,
                            "🚗 Request <b>" + requestNumber + "</b> marked as <b>In Progress</b>.\nSafe driving!",
                            List.of(
                                    Map.of("text", "⏳ View Pending", "callback_data", "menu_pending"),
                                    Map.of("text", "🏠 Main Menu",   "callback_data", "list_back")
                            ));
                } else {
                    apiClient.sendText(chatId, "⚠️ Could not update <b>" + requestNumber + "</b>. Please try again.");
                }
            }
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
                            "✅ Request <b>" + requestNumber + "</b> marked as <b>Completed</b>.\nThe citizen has been notified. 🎉",
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
                            "🚗 Request <b>" + requestNumber + "</b> marked as <b>In Progress</b>.\nSafe driving!",
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
        session.setPendingAction(null);

        if (token == null) {
            apiClient.sendText(chatId, "⚠️ Could not process rejection for <b>" + requestNumber + "</b>. Token expired or not found.");
            return;
        }

        boolean ok = elocateClient.rejectPickup(token, reason);
        if (ok) {
            apiClient.sendButtons(chatId,
                    "❌ Request <b>" + requestNumber + "</b> marked as <b>Rejected</b>.\nReason: <i>" + reason + "</i>\nThe citizen has been notified.",
                    List.of(
                            Map.of("text", "⏳ View Pending", "callback_data", "menu_pending"),
                            Map.of("text", "🏠 Main Menu",   "callback_data", "list_back")
                    ));
        } else {
            apiClient.sendText(chatId, "⚠️ Something went wrong for <b>" + requestNumber + "</b>. Please try again.");
        }
    }

    // -------------------------------------------------------------------------
    // Message builders
    // -------------------------------------------------------------------------

    private void sendMainMenu(Long chatId) {
        apiClient.sendButtons(chatId,
                "🏠 <b>Main Menu</b>\n\nWhat would you like to check?",
                List.of(
                        Map.of("text", "⏳ Pending Pickups",   "callback_data", "menu_pending"),
                        Map.of("text", "✅ Completed",          "callback_data", "menu_completed"),
                        Map.of("text", "⚙️ Process Request",   "callback_data", "menu_process"),
                        Map.of("text", "👤 My Profile",         "callback_data", "menu_profile")
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
            title = "✅ <b>Completed Pickups</b>";
        } else {
            page = requestRepo.findByAssignedDriverIdAndFulfillmentStatusIn(
                    session.getDriverId(),
                    List.of(FulfillmentStatus.PICKUP_ASSIGNED, FulfillmentStatus.PICKUP_IN_PROGRESS),
                    pageable);
            title = "⏳ <b>Pending Pickups</b>";
        }

        if (page.isEmpty() && session.getListOffset() == 0) {
            apiClient.sendButtons(chatId, title + "\n\nNo records found.",
                    List.of(Map.of("text", "⬅ Back to Menu", "callback_data", "list_back")));
            return;
        }

        StringBuilder sb = new StringBuilder(title).append("\n\n");
        int index = session.getListOffset() + 1;
        for (RecycleRequest r : page.getContent()) {
            sb.append("<b>").append(index++).append(". ").append(r.getRequestNumber()).append("</b>\n");
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

        String msg = "👤 <b>Your Profile</b>\n\n" +
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
                "👋 Welcome to <b>ELocate Driver Bot</b>!\n\nPlease enter your registered email address to get started.");
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 2) return email;
        return email.charAt(0) + "***" + email.substring(at);
    }
}
