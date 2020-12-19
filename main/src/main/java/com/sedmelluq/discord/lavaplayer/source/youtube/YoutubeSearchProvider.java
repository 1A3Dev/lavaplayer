package com.sedmelluq.discord.lavaplayer.source.youtube;

import com.sedmelluq.discord.lavaplayer.tools.DataFormatTools;
import com.sedmelluq.discord.lavaplayer.tools.ExceptionTools;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.http.ExtendedHttpConfigurable;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.*;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.StringEntity;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles processing YouTube searches.
 */
public class YoutubeSearchProvider implements YoutubeSearchResultLoader {
  private static final Logger log = LoggerFactory.getLogger(YoutubeSearchProvider.class);

  private static final String WATCH_URL_PREFIX = "https://www.youtube.com/watch?v=";
  private static final String YT_MUSIC_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30";
  private static final String YT_MUSIC_PAYLOAD = "{\"context\":{\"client\":{\"clientName\":\"WEB_REMIX\",\"clientVersion\":\"0.1\"}},\"query\":\"%s\",\"params\":\"Eg-KAQwIARAAGAAgACgAMABqChADEAQQCRAFEAo=\"}";
  private final HttpInterfaceManager httpInterfaceManager;
  private final Pattern polymerInitialDataRegex = Pattern.compile("(window\\[\"ytInitialData\"]|var ytInitialData)\\s*=\\s*(.*);");
  private final Pattern ytMusicDataRegex = Pattern.compile("<body>\\s*(.*)\\s*</body>");

  public YoutubeSearchProvider() {
    this.httpInterfaceManager = HttpClientTools.createCookielessThreadLocalManager();
  }

  public ExtendedHttpConfigurable getHttpConfiguration() {
    return httpInterfaceManager;
  }

  /**
   * @param query Search query.
   * @param ytMusic boolean
   * @return Playlist of the first page of results.
   */
  @Override
  public AudioItem loadSearchResult(String query, Function<AudioTrackInfo, AudioTrack> trackFactory, Boolean ytMusic) {
    log.debug("Performing a search with query {}", query);

    try (HttpInterface httpInterface = httpInterfaceManager.getInterface()) {
      URI url;
      if (ytMusic) {
        url = new URIBuilder("https://music.youtube.com/youtubei/v1/search")
                .addParameter("alt", "json")
                .addParameter("key", YT_MUSIC_KEY).build();
      } else {
        url = new URIBuilder("https://www.youtube.com/results")
                .addParameter("search_query", query).build();
      }

      if (ytMusic) {
        HttpPost post = new HttpPost(url);
        StringEntity payload = new StringEntity(String.format(YT_MUSIC_PAYLOAD, query), "UTF-8");
        post.setHeader("Referer", "music.youtube.com");
        post.setEntity(payload);
        try (CloseableHttpResponse response = httpInterface.execute(post)) {
          return searchQuery(query, trackFactory, response, true);
        }
      } else {
        try (CloseableHttpResponse response = httpInterface.execute(new HttpGet(url))) {
          return searchQuery(query, trackFactory, response, false);
        }
      }
    } catch (Exception e) {
      throw ExceptionTools.wrapUnfriendlyExceptions(e);
    }
  }

  private AudioItem searchQuery(String query, Function<AudioTrackInfo, AudioTrack> trackFactory,
                                CloseableHttpResponse response, Boolean ytMusic) throws IOException {
    int statusCode = response.getStatusLine().getStatusCode();
    if (!HttpClientTools.isSuccessWithContent(statusCode)) {
      throw new IOException("Invalid status code for search response: " + statusCode);
    }

    Document document = Jsoup.parse(response.getEntity().getContent(), StandardCharsets.UTF_8.name(), "");
    return extractSearchResults(document, query, trackFactory, ytMusic);
  }

  private AudioItem extractSearchResults(Document document, String query,
                                         Function<AudioTrackInfo, AudioTrack> trackFactory, Boolean ytMusic) {

    List<AudioTrack> tracks = new ArrayList<>();
    Elements resultsSelection = document.select("#page > #content #results");
    if (ytMusic) {
      log.debug("Attempting to parse results from ytMusic page");
      try {
        tracks = extractMusicTracks(document, trackFactory);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    } else if (!resultsSelection.isEmpty()) {
      for (Element results : resultsSelection) {
        for (Element result : results.select(".yt-lockup-video")) {
          if (!result.hasAttr("data-ad-impressions") && result.select(".standalone-ypc-badge-renderer-label").isEmpty()) {
            extractTrackFromResultEntry(tracks, result, trackFactory);
          }
        }
      }
    } else {
      log.debug("Attempting to parse results page as polymer");
      try {
        tracks = polymerExtractTracks(document, trackFactory);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    if (tracks.isEmpty()) {
      return AudioReference.NO_TRACK;
    } else {
      return new BasicAudioPlaylist("Search results for: " + query, tracks, null, true);
    }
  }

  private void extractTrackFromResultEntry(List<AudioTrack> tracks, Element element,
                                           Function<AudioTrackInfo, AudioTrack> trackFactory) {

    Element durationElement = element.select("[class^=video-time]").first();
    Element contentElement = element.select(".yt-lockup-content").first();
    String videoId = element.attr("data-context-item-id");

    if (durationElement == null || contentElement == null || videoId.isEmpty()) {
      return;
    }

    long duration = DataFormatTools.durationTextToMillis(durationElement.text());

    String title = contentElement.select(".yt-lockup-title > a").text();
    String author = contentElement.select(".yt-lockup-byline > a").text();

    AudioTrackInfo info = new AudioTrackInfo(title, author, duration, videoId, false,
        WATCH_URL_PREFIX + videoId,
            Collections.singletonMap("artworkUrl", String.format("https://i.ytimg.com/vi_webp/%s/maxresdefault.webp", videoId)));

    tracks.add(trackFactory.apply(info));
  }

  private List<AudioTrack> polymerExtractTracks(Document document, Function<AudioTrackInfo, AudioTrack> trackFactory) throws IOException {
    // Match the JSON from the HTML. It should be within a script tag
    Matcher matcher = polymerInitialDataRegex.matcher(document.outerHtml());
    if (!matcher.find()) {
      log.warn("Failed to match ytInitialData JSON object");
      return Collections.emptyList();
    }

    JsonBrowser jsonBrowser = JsonBrowser.parse(matcher.group(2));
    ArrayList<AudioTrack> list = new ArrayList<>();
    jsonBrowser.get("contents")
        .get("twoColumnSearchResultsRenderer")
        .get("primaryContents")
        .get("sectionListRenderer")
        .get("contents")
        .index(0)
        .get("itemSectionRenderer")
        .get("contents")
        .values()
        .forEach(json -> {
          AudioTrack track = extractPolymerData(json, trackFactory);
          if (track != null) list.add(track);
        });
    return list;
  }

  private AudioTrack extractPolymerData(JsonBrowser json, Function<AudioTrackInfo, AudioTrack> trackFactory) {
    json = json.get("videoRenderer");
    if (json.isNull()) return null; // Ignore everything which is not a track

    String title = json.get("title").get("runs").index(0).get("text").text();
    String author = json.get("ownerText").get("runs").index(0).get("text").text();
    if (json.get("lengthText").isNull()) {
      return null; // Ignore if the video is a live stream
    }
    long duration = DataFormatTools.durationTextToMillis(json.get("lengthText").get("simpleText").text());
    String videoId = json.get("videoId").text();

    AudioTrackInfo info = new AudioTrackInfo(title, author, duration, videoId, false,
        WATCH_URL_PREFIX + videoId,
            Collections.singletonMap("artworkUrl", PBJUtils.getBestThumbnail(json, videoId)));

    return trackFactory.apply(info);
  }

  private List<AudioTrack> extractMusicTracks(Document document, Function<AudioTrackInfo, AudioTrack> trackFactory) throws IOException {
    Matcher matcher = ytMusicDataRegex.matcher(document.outerHtml());
    if (!matcher.find()) {
      log.warn("Failed to match ytMusicData JSON object");
      return Collections.emptyList();
    }

    JsonBrowser jsonBrowser = JsonBrowser.parse(matcher.group(1));
    ArrayList<AudioTrack> list = new ArrayList<>();
    JsonBrowser tracks = jsonBrowser.get("contents")
            .get("sectionListRenderer")
            .get("contents")
            .index(0)
            .get("musicShelfRenderer")
            .get("contents");
    if (tracks == JsonBrowser.NULL_BROWSER) {
      tracks = jsonBrowser.get("contents")
              .get("sectionListRenderer")
              .get("contents")
              .index(1)
              .get("musicShelfRenderer")
              .get("contents");
    }
    tracks.values().forEach(json -> {
          AudioTrack track = extractMusicData(json, trackFactory);
          if (track != null) list.add(track);
        });
    return list;
  }

  private AudioTrack extractMusicData(JsonBrowser json, Function<AudioTrackInfo, AudioTrack> trackFactory) {
    JsonBrowser thumbnail = json.get("musicResponsiveListItemRenderer").get("thumbnail").get("musicThumbnailRenderer");
    JsonBrowser columns = json.get("musicResponsiveListItemRenderer").get("flexColumns");
    JsonBrowser firstColumn = columns.index(0)
        .get("musicResponsiveListItemFlexColumnRenderer")
        .get("text")
        .get("runs")
        .index(0);
    String title = firstColumn.get("text").text();
    String videoId = firstColumn.get("navigationEndpoint")
        .get("watchEndpoint")
        .get("videoId").text();
    List<JsonBrowser> secondColumn = columns.index(1)
        .get("musicResponsiveListItemFlexColumnRenderer")
        .get("text")
        .get("runs").values();
    String author = secondColumn.get(0)
        .get("text").text();
    long duration = DataFormatTools.durationTextToMillis(secondColumn.get(secondColumn.size() - 1)
        .get("text").text());

    AudioTrackInfo info = new AudioTrackInfo(title, author, duration, videoId, false,
            WATCH_URL_PREFIX + videoId,
            Collections.singletonMap("artworkUrl", PBJUtils.getYoutubeMusicThumbnail(thumbnail, videoId)));

    return trackFactory.apply(info);
  }
}
