package com.fintech.platform.common.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

class PageResponseTest {

    private record Row(String id) {}

    @Test
    @DisplayName("exposes a stable shape instead of serialising Spring's PageImpl internals")
    void flattensPage() {
        Pageable pageable = PageRequest.of(1, 2);
        PageImpl<Row> page = new PageImpl<>(List.of(new Row("a"), new Row("b")), pageable, 7);

        PageResponse<Row> response = PageResponse.from(page);

        assertThat(response.content()).containsExactly(new Row("a"), new Row("b"));
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(7);
        assertThat(response.totalPages()).isEqualTo(4);
        assertThat(response.first()).isFalse();
        assertThat(response.last()).isFalse();
    }

    @Test
    @DisplayName("maps entities to DTOs at the edge so no entity can escape into the serialiser")
    void mapsToDto() {
        Pageable pageable = PageRequest.of(0, 10);
        PageImpl<Row> page = new PageImpl<>(List.of(new Row("a")), pageable, 1);

        PageResponse<String> response = PageResponse.from(page, Row::id);

        assertThat(response.content()).containsExactly("a");
    }

    @Test
    void emptyIsWellFormed() {
        assertThat(PageResponse.<Row>empty().content()).isEmpty();
        assertThat(PageResponse.<Row>empty().totalElements()).isZero();
    }

    @Test
    @DisplayName("content is defensively copied so a caller cannot mutate the response after creation")
    void contentIsImmutable() {
        List<Row> mutable = new java.util.ArrayList<>(List.of(new Row("a")));
        PageResponse<Row> response = PageResponse.from(new PageImpl<>(mutable, PageRequest.of(0, 1), 1));

        mutable.clear();

        assertThat(response.content()).hasSize(1);
        assertThatThrownBy(() -> response.content().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNonsensicalPaging() {
        assertThatThrownBy(() -> new PageResponse<>(List.of(), -1, 10, 0, 0, true, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PageResponse<>(List.of(), 0, 0, 0, 0, true, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
