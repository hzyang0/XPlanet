package com.xplanet.booking;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import org.springframework.format.FormatterRegistry;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.*;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Keep the database's UTC convention while making the offset explicit in every API response. */
@Configuration
public class UtcJsonConfig implements WebMvcConfigurer {
  /** Offset-bearing inputs denote instants; legacy offset-free inputs already denote UTC. */
  static LocalDateTime parseUtc(String value) {
    TemporalAccessor parsed = DateTimeFormatter.ISO_DATE_TIME.parseBest(
        value, OffsetDateTime::from, LocalDateTime::from);
    return parsed instanceof OffsetDateTime offset
        ? offset.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()
        : LocalDateTime.from(parsed);
  }

  @Override
  public void addFormatters(FormatterRegistry registry) {
    registry.addConverter(String.class, LocalDateTime.class, UtcJsonConfig::parseUtc);
  }

  @Bean
  Jackson2ObjectMapperBuilderCustomizer utcDateTimes() {
    return builder -> builder.serializerByType(LocalDateTime.class, new JsonSerializer<LocalDateTime>() {
      @Override
      public void serialize(LocalDateTime value, JsonGenerator json, SerializerProvider provider) throws IOException {
        json.writeString(value.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
      }
    }).deserializerByType(LocalDateTime.class, new JsonDeserializer<LocalDateTime>() {
      @Override
      public LocalDateTime deserialize(JsonParser json, DeserializationContext context) throws IOException {
        if (!json.hasToken(JsonToken.VALUE_STRING))
          return (LocalDateTime) context.handleUnexpectedToken(LocalDateTime.class, json);
        String value = json.getText();
        try {
          return parseUtc(value);
        } catch (DateTimeException error) {
          throw context.weirdStringException(value, LocalDateTime.class, "需要 ISO 日期时间，可带 UTC 偏移");
        }
      }
    });
  }
}
