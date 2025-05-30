package de.remsfal.core.json;

import de.remsfal.core.model.ProjectMemberModel;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectMemberListJsonTest {

    @Test
    void testValueOfSet() {
        Set<ProjectMemberModel> models = new HashSet<>();
        ProjectMemberModel model1 = new EntityProjectMemberModel(
            "1", "John", "john@example.com", true, ProjectMemberModel.MemberRole.LESSOR);
        models.add(model1);
        ProjectMemberListJson result = ProjectMemberListJson.valueOfSet(models);
        assertEquals(1, result.getMembers().size());
    }

    static class EntityProjectMemberModel implements ProjectMemberModel {
        private String id;
        private String name;
        private String email;
        private boolean isActive;
        private MemberRole role;
        public EntityProjectMemberModel(String id, String name, String email, boolean isActive, MemberRole role) {
            this.id = id;
            this.name = name;
            this.email = email;
            this.isActive = isActive;
            this.role = role;
        }
        @Override
        public String getId() {
            return id;
        }
        @Override
        public String getName() {
            return name;
        }
        @Override
        public String getEmail() {
            return email;
        }
        @Override
        public MemberRole getRole() {
            return role;
        }
        @Override
        public Boolean isActive() {
            return isActive;
        }
    }
}
