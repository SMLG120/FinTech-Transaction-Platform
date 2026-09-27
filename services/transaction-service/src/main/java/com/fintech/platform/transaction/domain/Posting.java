package com.fintech.platform.transaction.domain;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A balanced set of journal legs: the description of a movement of value, before it is attached to
 * any account row.
 *
 * <p><b>The invariant is enforced here, at construction.</b> A {@code Posting} cannot exist in which the
 * debits do not equal the credits, so the ledger cannot record an unbalanced movement, and no
 * reconciler is needed to discover one afterwards. That is the entire reason this type is separate
 * from the JPA entities: the check belongs where it cannot be skipped, and a service method that
 * builds a posting by hand is a service method that cannot skip it.
 *
 * <p>Legs name an account <em>type</em> rather than an account id. Resolving a type to a row is the
 * service's job, and keeping ids out of here is what lets this class be a pure value with no
 * persistence knowledge and no way to depend on one.
 *
 * <p>All legs of one posting share a currency. There is no multi-currency posting because there is no
 * foreign-exchange rate source in this platform, and a posting that spans two currencies would need
 * one to balance.
 *
 * <p>Immutable, and {@link #withReversalOf} produces the mirrored posting a reversal needs rather than
 * leaving reversal arithmetic to a call site.
 */
public final class Posting {

    private final JournalEntryKind kind;
    private final List<Leg> legs;

    private Posting(JournalEntryKind kind, List<Leg> legs) {
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.legs = List.copyOf(legs);
    }

    /**
     * Builds a posting from legs, rejecting anything unbalanced.
     *
     * @throws IllegalArgumentException if the debits and credits differ, if a leg is not positive, or
     *     if the legs span more than one currency
     */
    public static Posting balanced(JournalEntryKind kind, List<Leg> legs) {
        Objects.requireNonNull(legs, "legs must not be null");
        if (legs.size() < 2) {
            // A one-legged posting is money appearing from or vanishing into nowhere, which is the
            // exact failure double-entry exists to make impossible.
            throw new IllegalArgumentException("a posting needs at least two legs, got " + legs.size());
        }
        long debits = 0;
        long credits = 0;
        Set<java.util.Currency> currencies = new HashSet<>();
        for (Leg leg : legs) {
            if (!leg.amount().isPositive()) {
                throw new IllegalArgumentException("every leg must be positive; sign belongs to the direction: " + leg);
            }
            currencies.add(leg.amount().currency());
            if (leg.direction() == PostingDirection.DEBIT) {
                debits = Math.addExact(debits, leg.amount().minorUnits());
            } else {
                credits = Math.addExact(credits, leg.amount().minorUnits());
            }
        }
        if (currencies.size() != 1) {
            throw new IllegalArgumentException("a posting must be in one currency, got " + currencies);
        }
        if (debits != credits) {
            throw new IllegalArgumentException("a posting must balance: debits " + debits + " != credits " + credits);
        }
        return new Posting(kind, legs);
    }

    public JournalEntryKind kind() {
        return kind;
    }

    public List<Leg> legs() {
        return legs;
    }

    /** The currency every leg shares. */
    public java.util.Currency currency() {
        return legs.get(0).amount().currency();
    }

    /** The amount of the posting, which is the same on every leg by the balancing rule. */
    public Money amount() {
        return legs.get(0).amount();
    }

    /** The distinct account types this posting touches, in declaration order. */
    public Set<LedgerAccountType> accountTypes() {
        Set<LedgerAccountType> types = EnumSet.noneOf(LedgerAccountType.class);
        for (Leg leg : legs) {
            types.add(leg.accountType());
        }
        return types;
    }

    /**
     * The mirror image of this posting, marked as reversing {@code original}.
     *
     * <p>Every leg's direction is flipped and nothing else changes, which is what makes a reversal
     * auditable: the entry says exactly which entry it undoes, and the arithmetic is the same shape
     * run backwards rather than a separately-derived set of numbers that happens to match.
     */
    public Posting asReversalOf(Posting original) {
        List<Leg> mirrored = new ArrayList<>(legs.size());
        for (Leg leg : legs) {
            mirrored.add(new Leg(leg.accountType(), leg.direction().opposite(), leg.amount()));
        }
        return new Posting(JournalEntryKind.REVERSAL, mirrored);
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder(kind.name()).append('[');
        for (int i = 0; i < legs.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(legs.get(i));
        }
        return out.append(']').toString();
    }

    /**
     * One side of a posting: an account type, a direction and a positive amount.
     *
     * <p>The amount is always positive and the direction always carries the sign. A signed amount on a
     * leg would make "debit -100" and "credit 100" two spellings of the same fact, and a ledger where
     * both appear is a ledger whose balance is wrong by a factor of two somewhere and impossible to
     * find by reading.
     */
    public record Leg(LedgerAccountType accountType, PostingDirection direction, Money amount) {

        public Leg {
            Objects.requireNonNull(accountType, "accountType must not be null");
            Objects.requireNonNull(direction, "direction must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
        }
    }
}
