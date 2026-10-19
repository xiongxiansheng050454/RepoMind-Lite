package com.repomind.lite.infrastructure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class VectorValidationTest {
    @Test
    void validatesDimensionsAndFiniteCoordinates() {
        assertThatThrownBy(() -> IndexStore.vector(new float[3])).isInstanceOf(IllegalArgumentException.class);
        float[] invalid = new float[1536];
        invalid[5] = Float.NaN;
        assertThatThrownBy(() -> IndexStore.vector(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThat(IndexStore.vector(new float[1536])).startsWith("[0.0,").endsWith("]");
    }
}
