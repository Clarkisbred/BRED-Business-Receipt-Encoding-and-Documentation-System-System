package com.bred.rms.services;

import com.bred.rms.models.Project;
import com.bred.rms.models.Receipt;
import com.bred.rms.models.ValidationException;
import com.bred.rms.repositories.ReceiptRepository;
import com.bred.rms.repositories.ProjectRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Project rules: required fields, unique code, and delete only when no receipt uses it (admin only). */
public final class ProjectService {
    private final ProjectRepository repo;
    private final ReceiptRepository receipts;
    private final CurrentUser who;

    public ProjectService(ProjectRepository repo, ReceiptRepository receipts, CurrentUser who) {
        this.repo = repo;
        this.receipts = receipts;
        this.who = who;
    }

    public List<Project> findAll() { return repo.findAll(); }

    /** Validates and saves (null id creates). */
    public Project save(Project v) {
        Guard.requireSignedIn(who);
        if (Guard.blank(v.name())) throw new ValidationException("Project name is required.");
        if (Guard.blank(v.code())) throw new ValidationException("Project code is required.");
        if (who.get().role() != com.bred.rms.models.Role.CREATOR) {
            for (Project other : repo.findAll()) {
                if (!other.id().equals(v.id()) && Guard.norm(other.code()).equals(Guard.norm(v.code()))) {
                    throw new ValidationException("A project with that code already exists.");
                }
            }
        }
        return repo.save(v);
    }

    /** Deletes a project; blocked while receipts still use it. Administrators only. */
    public void delete(UUID id) {
        Guard.requireAdmin(who, "Only administrators can delete projects.");
        long used = receipts.findAll().stream().filter((Receipt r) -> id.equals(r.projectId())).count();
        if (used > 0) throw new ValidationException("Cannot delete: " + used + " receipt(s) use this project.");
        repo.delete(id);
    }

    public Optional<Receipt> findById(UUID projectId) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'findById'");
    }
}
