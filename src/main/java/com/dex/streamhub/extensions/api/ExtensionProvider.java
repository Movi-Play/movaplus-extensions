package com.dex.streamhub.extensions.api;

import java.util.List;
import java.util.Map;

/**
 * Contrato que toda extensión nativa de StreamHub/MovaPlus implementa.
 * Debe existir IDÉNTICO en la app y en cada proyecto de extensión — cópialo,
 * no lo dupliques con cambios, o el cast en ExtensionLoader va a fallar.
 */
public interface ExtensionProvider {

    String getName();

    int getVersion();

    List<Map<String, String>> fetchCatalog(String type, int page) throws Exception;

    List<Map<String, String>> fetchSearch(String query, int page) throws Exception;

    Map<String, Object> fetchDetail(String detailUrl) throws Exception;

    List<Map<String, String>> fetchServers(String episodeUrl) throws Exception;
}
