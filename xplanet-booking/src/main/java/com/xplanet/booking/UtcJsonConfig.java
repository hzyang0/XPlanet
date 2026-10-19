package com.xplanet.booking;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.*;

/** Keep the database's UTC convention while making the offset explicit in every API response. */
@Configuration
public class UtcJsonConfig {
  @Bean
  Jackson2ObjectMapperBuilderCustomizer utcDateTimes() {
    return builder -> builder.serializerByType(LocalDateTime.class, new JsonSerializer<LocalDateTime>() {
      @Override
      public void serialize(LocalDateTime value, JsonGenerator json, SerializerProvider provider) throws IOException {
        json.writeString(value.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
      }
    });
  }
}
