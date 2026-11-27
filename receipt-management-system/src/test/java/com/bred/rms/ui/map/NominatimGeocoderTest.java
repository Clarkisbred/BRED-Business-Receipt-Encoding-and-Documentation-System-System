package com.bred.rms.ui.map;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NominatimGeocoderTest {
    @Test
    void searchesAndParsesNominatimLocations() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            byte[] body = """
                    [{"display_name":"Cebu City, Cebu, Philippines","lat":"10.3156990","lon":"123.8854370",
                      "type":"city","osm_type":"relation","osm_id":12345},
                     {"display_name":"Cebu Technological University, Cebu","lat":"10.2992","lon":"123.8988",
                      "type":"university","osm_type":"way","osm_id":12346}]
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/search");
            NominatimGeocoder geocoder = new NominatimGeocoder(endpoint);
            var results = geocoder.search("Cebu");

            assertEquals(2, results.size());
            assertEquals("Cebu City, Cebu, Philippines", results.get(0).displayName());
            assertEquals(10.3156990, results.get(0).latitude());
            assertEquals(123.8854370, results.get(0).longitude());
            assertFalse(results.get(1).displayName().isBlank());
        } finally {
            server.stop(0);
        }
    }
}
