package com.bred.rms.api;

import com.bred.rms.models.User;
import com.bred.rms.services.AuthException;
import com.bred.rms.security.Module;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

interface LocalApiBackend {
    User login(String email, char[] password) throws AuthException;
    User current();
    void logout();
    LocalApiServer.DashboardData dashboard();
    default LocalApiServer.ModulePageData modulePage(Module module) {
        throw new UnsupportedOperationException("React workspace data is unavailable.");
    }
    default LocalApiServer.ModulePageData modulePage(Module module, String table) {
        return modulePage(module);
    }
    default LocalApiServer.ModulePageData moduleAction(Module module, String action, String id, JsonNode values) {
        throw new UnsupportedOperationException("React workspace actions are unavailable.");
    }
    default List<LocalApiServer.MapSearchResult> mapSearch(String query) {
        throw new UnsupportedOperationException("Project location search is unavailable.");
    }
    default LocalApiServer.ThemeData theme() {
        return LocalApiServer.ThemeData.from(com.bred.rms.ui.theme.ThemeManager.reactTheme());
    }
}
