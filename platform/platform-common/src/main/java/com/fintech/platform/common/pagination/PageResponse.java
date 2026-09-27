package com.fintech.platform.common.pagination;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * The platform's pagination response contract.
 *
 * <p>Wrapping Spring Data's {@code Page} rather than returning it directly is intentional:
 *
 * <ul>
 *   <li>{@code PageImpl} serialises its internal {@code Pageable} back to the caller, which leaks a
 *       server-side type and changes the response shape whenever the framework changes.
 *   <li>This shape is a stable, documented API contract that the React client and the Postman
 *       collection can both rely on.
 *   <li>Content is mapped through a function, which keeps entity-to-DTO translation explicit at the
 *       edge instead of letting a lazily-loaded entity escape into a serialiser.
 * </ul>
 *
 * @param <T> the item type
 */
public record PageResponse<T>(
        List<T> content, int page, int size, long totalElements, int totalPages, boolean first, boolean last) {

    public PageResponse {
        content = content == null ? List.of() : List.copyOf(content);
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size < 1) {
            throw new IllegalArgumentException("size must be >= 1");
        }
    }

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }

    /** Maps a page of entities to a page of DTOs without exposing the entity. */
    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }

    public static <T> PageResponse<T> single(List<T> content) {
        return new PageResponse<>(content, 0, Math.max(content.size(), 1), content.size(), 1, true, true);
    }

    /**
     * An empty result set. {@code size} is 1 rather than 0 because this field mirrors the page size
     * the caller asked for, and a page size of 0 is never a valid request.
     */
    public static <T> PageResponse<T> empty() {
        return new PageResponse<>(List.of(), 0, 1, 0, 0, true, true);
    }
}
