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

@Service
@Slf4j
@RequiredArgsConstructor
public class WhatsAppBotService {

    private static final int PAGE_SIZE = 5;

    private final WhatsAppSessionRepository sessionRepo;
    private final DriverRepository driverRepo;
    private final RecycleRequestRepository requestRepo;
    private final OtpService otpService;
    private final WhatsAppApiClient apiClient;
    private final ElocateServerClient elocateClient;

    // -------------------------------------------------------------------------
    // Entry point — called by the webhook controller for every incoming message
    // -------------------------------------------------------------------------

    @Transactional
    public void handleMessage(String phone, String text, String interactiveReplyId) {
        WhatsAppSession session = sessionRepo.findById(phone).orElseGet(() -> {
            WhatsAppSession s = new WhatsAppSession();
            s.setPhoneNumber(phone);
            s.setState("AWAITING_EMAIL");
            return s;
        });

        // Interactive button reply takes priority over raw text
        String input = (interactiveReplyId != null) ? interactiveReplyId : (text != null ? text.trim() : "");

        switch (session.getState()) {
            case "AWAITING_EMAIL"         -> handleEmail(session, input);
            case "AWAITING_OTP"           -> handleOtp(session, input);
            case "MAIN_MENU"              -> handleMainMenu(session, input);
            case "PENDING_LIST"           -> handleListNav(session, input, false);
            case "COMPLETED_LIST"         -> handleListNav(session, input, true);
            case "PROFILE"               -> handleProfile(session, input);
            case "AWAITING_REJECT_REASON" -> handleRejectReason(session, input);
            default                       -> resetToEmail(session);
        }

        sessionRepo.save(session);
    }

    // -------------------------------------------------------------------------
    // State handlers
    // -------------------------------------------------------------------------

    private void handleEmail(WhatsAppSession session, String email) {
        Optional<Driver> driverOpt = driverRepo.findByEmail(email.toLowerCase());
        if (driverOpt.isEmpty()) {
            apiClient.sendText(session.getPhoneNumber(),
                    "❌ That email is not registered as a driver in ELocate.\n\n" +
                    "Please check and try again, or contact your admin.");
            return;
        }

        Driver driver = driverOpt.get();
        session.setPendingEmail(driver.getEmail());
        session.setState("AWAITING_OTP");

        otpService.generateAndSend(session, driver.getEmail());

        apiClient.sendText(session.getPhoneNumber(),
                "✅ Found your account! An OTP has been sent to *" + maskEmail(driver.getEmail()) + "*.\n\n" +
                "Please enter the 6-digit OTP to continue.");
    }

    private void handleOtp(WhatsAppSession session, String input) {
        if (!otpService.verify(session, input)) {
            apiClient.sendText(session.getPhoneNumber(),
                    "❌ Invalid or expired OTP. Please try again.\n\nType your OTP:");
            return;
        }

        // Link phone to driver permanently
        Driver driver = driverRepo.findByEmail(session.getPendingEmail()).orElseThrow();
        session.setDriverId(driver.getId());
        session.setState("MAIN_MENU");
        otpService.clear(session);

        apiClient.sendText(session.getPhoneNumber(),
                "✅ Verified! Welcome, *" + driver.getName() + "* 👋");
        sendMainMenu(session.getPhoneNumber());
    }

    private void handleMainMenu(WhatsAppSession session, String input) {
        switch (input) {
            case "menu_pending"   -> {
                session.setState("PENDING_LIST");
                session.setListOffset(0);
                sendPickupList(session, false);
            }
            case "menu_completed" -> {
                session.setState("COMPLETED_LIST");
                session.setListOffset(0);
                sendPickupList(session, true);
            }
            case "menu_profile"   -> {
                session.setState("PROFILE");
                sendProfile(session);
            }
            default -> sendMainMenu(session.getPhoneNumber());
        }
    }

    private void handleListNav(WhatsAppSession session, String input, boolean completed) {
        // Pickup action buttons from proactive notifications
        if (input.startsWith("accept_pickup:") || input.startsWith("reject_pickup:") || input.startsWith("start_pickup:")) {
            handlePickupAction(session, input);
            return;
        }
        // Main menu buttons — driver may tap these from any state
        if (input.equals("menu_pending") || input.equals("menu_completed") || input.equals("menu_profile")) {
            session.setState("MAIN_MENU");
            handleMainMenu(session, input);
            return;
        }
        switch (input) {
            case "list_next" -> {
                session.setListOffset(session.getListOffset() + PAGE_SIZE);
                sendPickupList(session, completed);
            }
            case "list_back" -> {
                session.setState("MAIN_MENU");
                sendMainMenu(session.getPhoneNumber());
            }
            default -> sendPickupList(session, completed);
        }
    }

    private void handleProfile(WhatsAppSession session, String input) {
        if ("profile_back".equals(input)) {
            session.setState("MAIN_MENU");
            sendMainMenu(session.getPhoneNumber());
        } else {
            sendProfile(session);
        }
    }

    private void handlePickupAction(WhatsAppSession session, String input) {
        // input format: "accept_pickup:EL-2024-00145"
        String[] parts = input.split(":", 2);
        String action = parts[0];
        String requestNumber = parts.length > 1 ? parts[1] : "unknown";

        switch (action) {
            case "accept_pickup" -> {
                String token = elocateClient.resolveToken(requestNumber, "ACCEPT");
                if (token == null) {
                    apiClient.sendText(session.getPhoneNumber(),
                            "⚠️ Could not process accept for *" + requestNumber + "*.\n" +
                            "The action link may have expired. Please contact your intermediary.");
                    return;
                }
                boolean ok = elocateClient.acceptPickup(token);
                session.setState("MAIN_MENU");
                if (ok) {
                    apiClient.sendButtons(session.getPhoneNumber(),
                            "✅ Request *" + requestNumber + "* marked as *Completed*.\n" +
                            "The citizen has been notified via email. 🎉",
                            List.of(Map.of("id", "menu_pending", "title", "⏳ View Pending"),
                                    Map.of("id", "list_back",    "title", "🏠 Main Menu"))
                    );
                } else {
                    apiClient.sendText(session.getPhoneNumber(),
                            "⚠️ Something went wrong for *" + requestNumber + "*. Please try again or contact support.");
                }
            }
            case "start_pickup" -> {
                String token = elocateClient.resolveToken(requestNumber, "IN_PROGRESS");
                if (token == null) {
                    apiClient.sendText(session.getPhoneNumber(),
                            "⚠️ Could not mark *" + requestNumber + "* as In Progress. Link may have expired.");
                    return;
                }
                boolean ok = elocateClient.markInProgress(token);
                session.setState("MAIN_MENU");
                if (ok) {
                    apiClient.sendButtons(session.getPhoneNumber(),
                            "🚗 Request *" + requestNumber + "* marked as *In Progress*.\n" +
                            "Safe driving!",
                            List.of(Map.of("id", "menu_pending", "title", "⏳ View Pending"),
                                    Map.of("id", "list_back",    "title", "🏠 Main Menu"))
                    );
                } else {
                    apiClient.sendText(session.getPhoneNumber(),
                            "⚠️ Something went wrong for *" + requestNumber + "*. Please try again.");
                }
            }
            case "reject_pickup" -> {
                session.setState("AWAITING_REJECT_REASON");
                session.setPendingActionRequest(requestNumber);
                apiClient.sendText(session.getPhoneNumber(),
                        "❌ You are rejecting request *" + requestNumber + "*.\n\n" +
                        "Please type a brief reason for rejection:");
            }
        }
    }

    private void handleRejectReason(WhatsAppSession session, String reason) {
        String requestNumber = session.getPendingActionRequest();
        String token = elocateClient.resolveToken(requestNumber, "REJECT");

        session.setState("MAIN_MENU");
        session.setPendingActionRequest(null);

        if (token == null) {
            apiClient.sendText(session.getPhoneNumber(),
                    "⚠️ Could not process rejection for *" + requestNumber + "*. Link may have expired.");
            return;
        }

        boolean ok = elocateClient.rejectPickup(token, reason);
        if (ok) {
            apiClient.sendButtons(session.getPhoneNumber(),
                    "❌ Request *" + requestNumber + "* marked as *Rejected*.\n" +
                    "Reason: _" + reason + "_\n" +
                    "The citizen and intermediary have been notified.",
                    List.of(Map.of("id", "menu_pending", "title", "⏳ View Pending"),
                            Map.of("id", "list_back",    "title", "🏠 Main Menu"))
            );
        } else {
            apiClient.sendText(session.getPhoneNumber(),
                    "⚠️ Something went wrong for *" + requestNumber + "*. Please try again.");
        }
    }

    // -------------------------------------------------------------------------
    // Message builders
    // -------------------------------------------------------------------------

    private void sendMainMenu(String phone) {
        apiClient.sendButtons(phone,
                "🏠 *Main Menu*\n\nWhat would you like to check?",
                List.of(
                        Map.of("id", "menu_pending",   "title", "⏳ Pending Pickups"),
                        Map.of("id", "menu_completed", "title", "✅ Completed"),
                        Map.of("id", "menu_profile",   "title", "👤 My Profile")
                )
        );
    }

    private void sendPickupList(WhatsAppSession session, boolean completed) {
        PageRequest pageable = PageRequest.of(
                session.getListOffset() / PAGE_SIZE,
                PAGE_SIZE,
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

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
            apiClient.sendButtons(session.getPhoneNumber(),
                    title + "\n\nNo records found.",
                    List.of(Map.of("id", "list_back", "title", "⬅ Back to Menu"))
            );
            return;
        }

        StringBuilder sb = new StringBuilder(title).append("\n\n");
        int index = session.getListOffset() + 1;
        for (RecycleRequest r : page.getContent()) {
            sb.append("*").append(index++).append(". ").append(r.getRequestNumber()).append("*\n");
            if (r.getDeviceModel() != null) {
                sb.append("📱 ").append(r.getDeviceModel().getModelName()).append("\n");
            }
            if (r.getPickupAddress() != null) {
                sb.append("📍 ").append(r.getPickupAddress().getAddress())
                  .append(", ").append(r.getPickupAddress().getCity()).append("\n");
            }
            if (r.getPickupDate() != null) {
                sb.append("📅 ").append(r.getPickupDate()).append("\n");
            }
            sb.append("Status: ").append(r.getFulfillmentStatus()).append("\n\n");
        }

        boolean hasMore = page.hasNext();

        if (hasMore) {
            apiClient.sendButtons(session.getPhoneNumber(), sb.toString(),
                    List.of(
                            Map.of("id", "list_next", "title", "Next 5 ➡"),
                            Map.of("id", "list_back", "title", "⬅ Back to Menu")
                    )
            );
        } else {
            apiClient.sendButtons(session.getPhoneNumber(), sb.toString(),
                    List.of(Map.of("id", "list_back", "title", "⬅ Back to Menu"))
            );
        }
    }

    private void sendProfile(WhatsAppSession session) {
        Driver driver = driverRepo.findById(session.getDriverId()).orElse(null);
        if (driver == null) {
            resetToEmail(session);
            return;
        }

        String msg = "👤 *Your Profile*\n\n" +
                "Name: " + driver.getName() + "\n" +
                "Phone: " + driver.getPhone() + "\n" +
                "Vehicle: " + driver.getVehicleNumber() + " (" + driver.getVehicleType() + ")\n" +
                "Status: " + driver.getAvailability();

        apiClient.sendButtons(session.getPhoneNumber(), msg,
                List.of(Map.of("id", "profile_back", "title", "⬅ Back to Menu"))
        );
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void resetToEmail(WhatsAppSession session) {
        session.setState("AWAITING_EMAIL");
        session.setDriverId(null);
        apiClient.sendText(session.getPhoneNumber(),
                "👋 Welcome to *ELocate Driver Bot*!\n\nPlease enter your registered email address to get started.");
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 2) return email;
        return email.charAt(0) + "***" + email.substring(at);
    }
}
