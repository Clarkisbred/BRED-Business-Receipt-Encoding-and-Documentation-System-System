package com.bred.rms.api;

import com.bred.rms.models.User;
import com.bred.rms.services.AuthException;

interface LocalApiBackend {
    User login(String email, char[] password) throws AuthException;
    User current();
    void logout();
    LocalApiServer.DashboardData dashboard();
}
