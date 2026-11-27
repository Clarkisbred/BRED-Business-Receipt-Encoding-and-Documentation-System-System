package com.bred.rms.security;

import com.bred.rms.models.Role;

/** The screens of the application, in menu order. */
public enum Module {
    DASHBOARD("Dashboard", false, false), MESSAGES("Custom Messages", false, false), DUCKY("Ducky", false, false),
    RECEIPTS("Receipts", false, false),
    SUPPLIERS("Suppliers", false, false), PROJECTS("Projects", false, false),
    LIVE_MAP("Live Project Map", false, false),
    CONSTRUCTION("Construction", false, false),
    REPORTS("Reports", false, false), USERS("Users", true, false), ACTIVITY_LOG("Activity Log", true, false),
    COMPANIES("Companies", false, true), HISTORY("History", false, true), SETTINGS("Settings", false, false);

    private final String title;
    private final boolean adminOnly;
    private final boolean creatorOnly;

    Module(String title, boolean adminOnly, boolean creatorOnly) {
        this.title = title;
        this.adminOnly = adminOnly;
        this.creatorOnly = creatorOnly;
    }

    public String title() { return title; }

    public boolean allows(Role role) {
        return (!adminOnly || role != null && role.canManageCompany())
                && (!creatorOnly || role == Role.CREATOR);
    }
}
