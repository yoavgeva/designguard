package io.github.yoavgeva.designguard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.yoavgeva.designguard.SourceTree;
import io.github.yoavgeva.designguard.model.ClassFilter;
import io.github.yoavgeva.designguard.model.Violation;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RawIdentifiersCheckTest {

    private static final String OWNER_ID = fix("OwnerId");

    @TempDir
    private Path root;

    @Test
    void namesThePlainIdAfterWhatItIdentifies() {
        new SourceTree(root)
                .add("Owner", """
                        package shop;

                        public class Owner {
                            private Integer id;

                            public String getPet(Integer id) {
                                return "pet " + id;
                            }

                            public String findVisitById(Integer id) {
                                return "visit " + id;
                            }

                            public String rename(Integer id) {
                                return "owner " + id;
                            }

                            public void setId(Integer id) {
                                this.id = id;
                            }

                            public String setPetId(Integer id) {
                                return "pet " + id;
                            }
                        }
                        """)
                .add("OwnerRepository", """
                        package shop;

                        public interface OwnerRepository extends org.springframework.data.repository.Repository<Owner, Integer> {
                            Owner findById(Integer id);
                        }
                        """);

        List<Violation> violations = new RawIdentifiersCheck(List.of("shop..")).check(SourceReader.read(List.of(root)),
                ClassFilter.ALL);

        assertThat(violations).extracting(Violation::message, Violation::fix).containsExactly(
                tuple("Owner.id is a raw Integer identifier", OWNER_ID),
                tuple("Owner.getPet() takes id as a raw Integer identifier", fix("PetId")),
                tuple("Owner.findVisitById() takes id as a raw Integer identifier", fix("VisitId")),
                tuple("Owner.rename() takes id as a raw Integer identifier", OWNER_ID),
                tuple("Owner.setId() takes id as a raw Integer identifier", OWNER_ID),
                tuple("Owner.setPetId() takes id as a raw Integer identifier", fix("PetId")),
                tuple("OwnerRepository.findById() takes id as a raw Integer identifier", OWNER_ID));
    }

    private static String fix(String type) {
        return "declare record " + type + "(Integer value) and use it, so identifiers of different things can't be swapped";
    }
}
