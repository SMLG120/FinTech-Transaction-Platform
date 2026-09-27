package com.fintech.platform.notification.web;

import com.fintech.platform.common.identity.CurrentCaller;
import com.fintech.platform.common.pagination.PageResponse;
import com.fintech.platform.notification.domain.NotificationStatus;
import com.fintech.platform.notification.error.NotificationErrors;
import com.fintech.platform.notification.persistence.NotificationEntity;
import com.fintech.platform.notification.persistence.NotificationRepository;
import com.fintech.platform.notification.service.NotificationService;
import com.fintech.platform.notification.web.NotificationResponses.NotificationView;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The delivery log a support agent reads, and the retry they press.
 *
 * <p>Two reads and one action, and the split is the support workflow. "Was the customer told" is a
 * read; "tell them again" is the retry, and it refuses a notification that already went out, because
 * resending a SENT message is the duplicate the event-id uniqueness was supposed to prevent, reached
 * through the API instead of through the consumer.
 *
 * <p><b>Every handler resolves the caller and asks the authorization bean first.</b> Not as an
 * annotation, because the annotations elsewhere in this platform are inert; see {@link
 * NotificationAuthorization}. The gateway already established the identity, so this is a second
 * opinion at a boundary the gateway cannot enforce, not a second source of truth.
 *
 * <p><b>There is no customer-facing equivalent.</b> See {@link NotificationAuthorization} for why a
 * per-customer view is a promise this service cannot keep: it correlates on a digest it cannot map
 * back to a token.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private static final int MAX_PAGE_SIZE = 200;

    private final NotificationService notifications;

    private final NotificationRepository repository;

    private final NotificationAuthorization authorization;

    private final CurrentCaller caller;

    public NotificationController(
            NotificationService notifications,
            NotificationRepository repository,
            NotificationAuthorization authorization,
            CurrentCaller caller) {
        this.notifications = notifications;
        this.repository = repository;
        this.authorization = authorization;
        this.caller = caller;
    }

    /**
     * The delivery log, newest first, optionally filtered by status.
     *
     * @param status the status to filter by, or null for all
     * @param page zero-based page index
     * @param size page size, capped
     * @return the page of notifications
     */
    @GetMapping
    public PageResponse<NotificationView> list(
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        authorization.requireRead(caller.require());
        PageRequest window = page(page, size);
        Page<NotificationEntity> found = status == null
                ? repository.findAllByOrderByCreatedAtDesc(window)
                : repository.findByStatusOrderByCreatedAtDesc(status, window);
        return PageResponse.from(found, NotificationView::of);
    }

    /**
     * One notification.
     *
     * @param id the notification's id
     * @return the notification
     */
    @GetMapping("/{id}")
    public NotificationView get(@PathVariable UUID id) {
        authorization.requireRead(caller.require());
        return NotificationView.of(require(id));
    }

    /**
     * Every message about one payment, in the order they were recorded.
     *
     * <p>A single list rather than a filtered page, because a payment's messages are a handful of
     * rows — authorised, possibly scored, settled — and paginating them would make the support agent
     * ask for page two of a conversation.
     *
     * @param transactionId the payment
     * @return the payment's messages
     */
    @GetMapping("/by-transaction/{transactionId}")
    public PageResponse<NotificationView> byTransaction(@PathVariable UUID transactionId) {
        authorization.requireRead(caller.require());
        List<NotificationView> views = repository.findByTransactionIdOrderByCreatedAtAsc(transactionId).stream()
                .map(NotificationView::of)
                .toList();
        return PageResponse.single(views);
    }

    /**
     * Retries one failed notification, now.
     *
     * <p>A 200 either way: the retry ran and the body says what happened. Refusing a SENT
     * notification is a 409, because the message exists and the request conflicts with its state —
     * answering 404 would hide the actual problem behind a missing-resource error, and answering 200
     * with no action would leave the agent pressing retry again.
     *
     * @param id the notification's id
     * @return the notification after the attempt
     */
    @PostMapping("/{id}/retry")
    public ResponseEntity<NotificationView> retry(@PathVariable UUID id) {
        authorization.requireRetry(caller.require());
        require(id);
        return ResponseEntity.ok(NotificationView.of(notifications.retryOne(id)));
    }

    private NotificationEntity require(UUID id) {
        return repository
                .findById(id)
                .orElseThrow(() -> NotificationErrors.NOT_FOUND.exception("No notification with id " + id));
    }

    /**
     * A page window, with the size bounded.
     *
     * <p>No sort supplied, because the ordering is in the repository method names and a sort added
     * here would fight them for a derived query's single ordering clause. The bound is on the upper
     * side only: a caller asking for a million rows gets the cap rather than a 500, because the cap
     * is a policy statement and a 500 is a bug report.
     */
    private static PageRequest page(int page, int size) {
        return PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE));
    }
}
