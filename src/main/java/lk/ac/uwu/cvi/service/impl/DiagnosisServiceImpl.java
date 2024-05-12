package lk.ac.uwu.cvi.service.impl;

import lk.ac.uwu.cvi.dto.request.DiagnosisRequestDTO;
import lk.ac.uwu.cvi.dto.request.DiagnosisStimuliResultRequestDTO;
import lk.ac.uwu.cvi.dto.request.RequestDTO;
import lk.ac.uwu.cvi.dto.response.*;
import lk.ac.uwu.cvi.entity.Diagnosis;
import lk.ac.uwu.cvi.entity.DiagnosisStimuli;
import lk.ac.uwu.cvi.entity.Patient;
import lk.ac.uwu.cvi.entity.Stimuli;
import lk.ac.uwu.cvi.enums.Characteristic;
import lk.ac.uwu.cvi.enums.DiagnosisStatus;
import lk.ac.uwu.cvi.repository.DiagnosisRepository;
import lk.ac.uwu.cvi.repository.DiagnosisStimuliRepository;
import lk.ac.uwu.cvi.repository.PatientRepository;
import lk.ac.uwu.cvi.repository.StimuliRepository;
import lk.ac.uwu.cvi.service.DiagnosisService;
import lk.ac.uwu.cvi.service.ScoreCalculationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnosisServiceImpl implements DiagnosisService {

    // REPOSITORIES
    private final PatientRepository patientRepository;
    private final StimuliRepository stimuliRepository;
    private final DiagnosisRepository diagnosisRepository;
    private final DiagnosisStimuliRepository diagnosisStimuliRepository;

    // SERVICES
    private final ScoreCalculationService scoreCalculationService;

    @Override
    @Transactional
    public ResponseDTO createUpdate(RequestDTO request) {

        DiagnosisRequestDTO diagnosisRequest = (DiagnosisRequestDTO) request;
        Patient patient = patientRepository.findById(diagnosisRequest.getPatientId()).orElseThrow(() -> generateNotFoundException("Patient"));

        Diagnosis diagnosis;

        if (diagnosisRequest.getId() != null && diagnosisRequest.getId() != 0) {
            diagnosis = diagnosisRepository.findByIdAndPatient(diagnosisRequest.getId(), patient).orElseThrow(() -> generateNotFoundException("Diagnosis"));
            if (diagnosis.getStatus() == DiagnosisStatus.COMPLETED)
                throw generateCustomServiceException(401, "Diagnosis is completed. Can not update!");
        } else {
            diagnosis = new Diagnosis();
            diagnosis.setPatient(patient);
            diagnosis.setStatus(DiagnosisStatus.PENDING);
            diagnosis = diagnosisRepository.save(diagnosis);
        }

        Diagnosis finalDiagnosis = diagnosis;

        List<DiagnosisStimuli> diagnosisStimulus = diagnosisRequest.getStimulus().stream().map(s -> {
            DiagnosisStimuli diagnosisStimuli;
            if (s.getId() != null && s.getId() != 0) {
                diagnosisStimuli = diagnosisStimuliRepository.findById(s.getId()).orElseThrow(() -> generateNotFoundException("Diagnosis Stimuli"));
                if (!diagnosisStimuli.getStatus().equals(DiagnosisStatus.PENDING))
                    throw generateCustomServiceException(401, "Can not update diagnose stimuli. It is ongoing or completed!");
            } else {
                diagnosisStimuli = new DiagnosisStimuli();
                diagnosisStimuli.setStatus(DiagnosisStatus.PENDING);
            }
            Stimuli stimuli = stimuliRepository.findByIdAndCharacteristic(s.getStimuliId(), s.getCharacteristic()).orElseThrow(() -> generateNotFoundException("Stimuli"));
            diagnosisStimuli.setDiagnosis(finalDiagnosis);
            diagnosisStimuli.setStimuli(stimuli);
            diagnosisStimuli.setCharacteristic(s.getCharacteristic());
            return diagnosisStimuli;
        }).toList();

        diagnosisStimuliRepository.saveAll(diagnosisStimulus);

        return getSuccessResponse("Diagnosis details added to the DB!", getDiagnosisResponseFromEntity(finalDiagnosis));
    }

    @Override
    public ResponseDTO getById(Long id) {
        Diagnosis diagnosis = diagnosisRepository.findById(id).orElseThrow(() -> generateNotFoundException("Diagnosis"));
        return getSuccessResponse(null, getDiagnosisResponseFromEntity(diagnosis));
    }

    @Override
    public ResponseDTO search(RequestDTO request, Pageable pageable) {
        return null;
    }

    @Override
    public List<DiagnosisResponseDTO> getDiagnosesForPatient(Long patientId) {
        return diagnosisRepository.findAllByPatient_Id(patientId).stream().map(this::getDiagnosisResponseFromEntity).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public ResponseDTO startDiagnosisStimuliTest(Long id) {
        DiagnosisStimuli diagnosisStimuli = diagnosisStimuliRepository.findById(id).orElseThrow(() -> generateNotFoundException("Diagnosis Stimuli"));
        diagnosisStimuli.setStartDateTime(LocalDateTime.now());
        diagnosisStimuli.setStatus(DiagnosisStatus.QUEUED);
        diagnosisStimuli = diagnosisStimuliRepository.save(diagnosisStimuli);

        Diagnosis diagnosis = diagnosisStimuli.getDiagnosis();
        if (diagnosis.getStartDateTime() == null) {
            diagnosis.setStartDateTime(LocalDateTime.now());
            diagnosis.setStatus(DiagnosisStatus.IN_PROGRESS);
            diagnosisRepository.save(diagnosis);
        }

        return getSuccessResponse("Diagnosis stimuli test is started!", null);
    }

    @Override
    @Transactional
    public ResponseDTO endDiagnosisStimuliTest(RequestDTO request) {
        DiagnosisStimuliResultRequestDTO resultRequest = (DiagnosisStimuliResultRequestDTO) request;
        DiagnosisStimuli diagnosisStimuli = diagnosisStimuliRepository.findById(resultRequest.getDiagnosisStimuliId())
                .orElseThrow(() -> generateNotFoundException("Diagnosis Stimuli"));
        diagnosisStimuli.setEndDateTime(LocalDateTime.now());
        diagnosisStimuli.setStatus(DiagnosisStatus.COMPLETED);
        DiagnoseResultResponseDTO result = scoreCalculationService.calculateCharacteristicResult(resultRequest);
        diagnosisStimuli.setScore(result.score());
        diagnosisStimuli = diagnosisStimuliRepository.save(diagnosisStimuli);

        Diagnosis diagnosis = diagnosisStimuli.getDiagnosis();
        if (!diagnosisStimuliRepository.existsByEndDateTimeIsNullAndDiagnosisAndIdIsNot(diagnosis, diagnosisStimuli.getId())) {
            diagnosis.setEndDateTime(LocalDateTime.now());
            diagnosis.setStatus(DiagnosisStatus.COMPLETED);
            DiagnoseResultResponseDTO diagnoseResult = scoreCalculationService.calculateDiagnosisResult(diagnosis.getId());
            diagnosis.setScore(diagnoseResult.score());
            diagnosis.setPhase(diagnoseResult.phase());
            diagnosisRepository.save(diagnosis);
        }
        return getSuccessResponse("Diagnosis stimuli test is completed!", null);
    }

    @Override
    public ResponseDTO checkDiagnoseStimuliConductStatus() {
        DiagnosisStimuli currentStimuli = diagnosisStimuliRepository.findTopByStatusOrderByStartDateTimeAsc(DiagnosisStatus.QUEUED);

        DiagnoseStimuliConductResponseDTO result = currentStimuli == null ?
                new DiagnoseStimuliConductResponseDTO(false, null, null, null, null)
                : new DiagnoseStimuliConductResponseDTO(true, getCharacteristicId(currentStimuli.getCharacteristic()),
                currentStimuli.getStimuli().getResourceName(),
                "", currentStimuli.getId()
        );

        return new ResponseDTO(
                true,
                "Check is success!",
                result);
    }

    private Long getCharacteristicId(Characteristic characteristic) {
        return switch (characteristic) {
            case COLOR_PREFERENCE -> 1L;
            case ATTENTION_TO_LIGHT -> 2L;
            case ATTENTION_TO_MOVEMENT -> 3L;
            case VISUAL_LATENCY -> 4L;
            case PREFERRED_VISUAL_FIELD -> 5L;
            case VISUAL_COMPLEXITY -> 6L;
            case DIFFICULTY_IN_DISTANCE_VIEWING -> 7L;
            case ATYPICAL_VISUAL_REFLEXES -> 8L;
            case DIFFICULTY_IN_VISUAL_NOVELTY -> 9L;
            case ABSENCE_OF_VISUAL_GUIDED_REACH -> 10L;
        };
    }

    private DiagnosisResponseDTO getDiagnosisResponseFromEntity(Diagnosis diagnosis) {
        Patient patient = diagnosis.getPatient();
        return new DiagnosisResponseDTO(diagnosis.getId(),
                patient.getId(),
                patient.getRegistrationId(),
                patient.getFirstName() + " " + patient.getLastName(),
                patient.getAge(),
                diagnosis.getCreatedDateTime(),
                diagnosis.getStartDateTime(),
                diagnosis.getEndDateTime(),
                diagnosis.getScore(),
                diagnosis.getPhase(),
                diagnosis.getStatus(),
                getStimulusForDiagnosis(diagnosis));
    }

    private List<DiagnosisStimuliResponseDTO> getStimulusForDiagnosis(Diagnosis diagnosis) {
        List<DiagnosisStimuli> diagnosisStimulus = diagnosisStimuliRepository.findAllByDiagnosis_Id(diagnosis.getId());
        return diagnosisStimulus.stream().map(s -> new DiagnosisStimuliResponseDTO(
                s.getId(),
                s.getStimuli().getId(),
                s.getStimuli().getResourceName(),
                s.getStatus(),
                s.getCharacteristic(),
                s.getScore(),
                s.getStartDateTime(),
                s.getEndDateTime()
        )).toList();
    }
}