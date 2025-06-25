package no.sikt.graphitron.example.frontgen.components;

import java.util.function.Function;

public class GridColumnConfig<T> {
    private final String header;
    private final Function<T, ?> valueProvider;
    private final int flexGrow;
    private final String rendererStyle;

    public GridColumnConfig(Builder<T> builder) {
        this.header = builder.header;
        this.valueProvider = builder.valueProvider;
        this.flexGrow = builder.flexGrow;
        this.rendererStyle = builder.rendererStyle;
    }

    public String getHeader() {
        return header;
    }

    public Function<T, ?> getValueProvider() {
        return valueProvider;
    }

    public int getFlexGrow() {
        return flexGrow;
    }

    public String getRendererStyle() {
        return rendererStyle;
    }

    public static class Builder<T> {
        private String header;
        private Function<T, ?> valueProvider;
        private int flexGrow = 1;  // Default value
        private String rendererStyle = "";  // Default value

        public Builder<T> header(String header) {
            this.header = header;
            return this;
        }

        public Builder<T> valueProvider(Function<T, ?> valueProvider) {
            this.valueProvider = valueProvider;
            return this;
        }

        public Builder<T> flexGrow(int flexGrow) {
            this.flexGrow = flexGrow;
            return this;
        }

        public Builder<T> rendererStyle(String rendererStyle) {
            this.rendererStyle = rendererStyle;
            return this;
        }

        public GridColumnConfig<T> build() {
            if (header == null || valueProvider == null) {
                throw new IllegalStateException("Header and valueProvider must be set");
            }
            return new GridColumnConfig<>(this);
        }
    }

    // Convenience constructor methods
    public static <T> Builder<T> of(String header, Function<T, ?> valueProvider) {
        return new Builder<T>()
                .header(header)
                .valueProvider(valueProvider);
    }

    public static <T> Builder<T> of(String header, Function<T, ?> valueProvider, int flexGrow) {
        return new Builder<T>()
                .header(header)
                .valueProvider(valueProvider)
                .flexGrow(flexGrow);
    }
}