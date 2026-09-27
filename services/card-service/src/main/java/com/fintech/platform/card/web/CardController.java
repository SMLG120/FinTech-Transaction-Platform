package com.fintech.platform.card.web;

import com.fintech.platform.card.domain.CardStatus;
import com.fintech.platform.card.service.CardService;
import com.fintech.platform.card.service.CardView;
import com.fintech.platform.card.service.IssuedCard;
import com.fintech.platform.common.identity.CurrentCaller;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP surface for card-service.
 *
 * <p>Same shape and same discipline as customer-service's controller: bind a request, ask
 * {@link CurrentCaller} who is calling, hand both to a service that authorises. There is no ownership
 * check here and no cardholder identifier in any request body other than the {@code customerId} that
 * the eligibility call verifies against the caller's own identity. A check in a controller is one
 * endpoint away from being forgotten, and the endpoint that forgets it is the breach; putting the rule
 * in the service means a new endpoint that forgets to pass the identity does not compile.
 *
 * <p>No endpoint here returns a card number except {@link #issue}, and that one does so exactly once.
 * See {@link CardDtos.IssuedCardResponse}.
 */
@RestController
@RequestMapping("/api/v1/cards")
public class CardController {

    private final CardService cardService;
    private final CurrentCaller caller;

    public CardController(CardService cardService, CurrentCaller caller) {
        this.cardService = cardService;
        this.caller = caller;
    }

    /**
     * Issues a card to the calling cardholder.
     *
     * <p>The only response in the platform that contains a card number, and the only one that ever
     * will. The number exists in this process for the duration of the call and is discarded with the
     * response.
     *
     * <p>201 rather than 200, with a {@code Location} of the card. The location is the durable resource;
     * it is also the answer to "how do I get my card number again", which is that the resource does not
     * hold one and never will.
     */
    @PostMapping
    public ResponseEntity<CardDtos.IssuedCardResponse> issue(@Valid @RequestBody CardDtos.IssueCardRequest request) {
        IssuedCard issued = cardService.issue(caller.require(), request.customerId(), request.brand());
        return ResponseEntity.created(
                        URI.create("/api/v1/cards/" + issued.card().id()))
                .body(CardDtos.IssuedCardResponse.from(issued));
    }

    /** The calling cardholder's own cards. */
    @GetMapping
    public List<CardDtos.CardResponse> listOwn() {
        return cardService.listOwnCards(caller.require()).stream()
                .map(CardDtos.CardResponse::from)
                .toList();
    }

    /**
     * One card.
     *
     * <p>Reachable by the cardholder and by the staff reader set, and the same call serves both because
     * the service resolves ownership rather than the controller deciding who to ask.
     */
    @GetMapping("/{cardId}")
    public CardDtos.CardResponse get(@PathVariable UUID cardId) {
        return CardDtos.CardResponse.from(cardService.getCard(caller.require(), cardId));
    }

    /**
     * Suspends a card.
     *
     * <p>POST rather than PUT: freezing is a request to move a card to a state, not an attempt to set the
     * state, and the difference shows up in what a retry does. A PUT that arrives twice is idempotent
     * because the second one sets the same value; this is POST because the transition itself is the
     * event, and the service's state machine is what makes a second attempt a clear 409 instead of a
     * silent no-op.
     */
    @PostMapping("/{cardId}/freeze")
    public CardDtos.CardResponse freeze(@PathVariable UUID cardId) {
        return toResponse(cardService.freeze(caller.require(), cardId));
    }

    /** Returns a cardholder's own frozen card to service. */
    @PostMapping("/{cardId}/unfreeze")
    public CardDtos.CardResponse unfreeze(@PathVariable UUID cardId) {
        return toResponse(cardService.unfreeze(caller.require(), cardId));
    }

    /** Reports a card lost or stolen. Terminal for that card; the answer is a replacement. */
    @PostMapping("/{cardId}/lost")
    public CardDtos.CardResponse reportLost(@PathVariable UUID cardId) {
        return toResponse(cardService.reportLost(caller.require(), cardId));
    }

    /**
     * Closes a card.
     *
     * <p>DELETE, and it is a lie in the HTTP sense — the card is retained, not removed, because the
     * record of a card that existed and stopped is needed by any dispute raised later. That is also why
     * the service marks it cancelled rather than deleting the row, and why a cancelled card reads back
     * as {@link CardStatus#CANCELLED} instead of 404. A client that treats this as a hard delete will
     * draw the wrong conclusion from the 404 it gets for a card that was deleted twice.
     */
    @DeleteMapping("/{cardId}")
    public CardDtos.CardResponse cancel(@PathVariable UUID cardId) {
        return toResponse(cardService.cancel(caller.require(), cardId));
    }

    private static CardDtos.CardResponse toResponse(CardView view) {
        return CardDtos.CardResponse.from(view);
    }
}
