package lk.ac.uwu.cvi.repository;

import lk.ac.uwu.cvi.entity.Patient;
import lk.ac.uwu.cvi.entity.Stimuli;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StimuliRepository extends JpaRepository<Stimuli, Long> {
}