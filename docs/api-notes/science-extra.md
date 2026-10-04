# Extra science fact-check (items 1–10)

Done on 2026-10-04 as a supplement to `docs/api-notes/science-sources.md`, with the same status tags:
**VERIFIED**, **CORRECTED**, **NUANCE**, **COMPUTED**, **UNVERIFIED**.

The paywalled papers (Wiley/AGU, Elsevier, Springer) could not be opened from this machine, and Springer and
ScienceDirect refused automated access. For those papers I read the abstracts through the Crossref API. Where possible
I took numbers from NASA NTRS, NAIF, PDS or arXiv copies instead. The notes column says when this applies.

---

## Summary

| # | Topic | Use this | Status |
|---|---|---|---|
| 1 | Viking annual pressure cycle | Primary min **Ls ≈ 148–149°**. Primary max **Ls ≈ 260°**. Secondary min **≈ Ls 343°** (northern winter). Secondary max **≈ Ls 45–60°**. VL1 swings **6.9 → 9.0 mbar** around a **7.9 mbar** annual mean (min/mean 0.87, max/mean 1.14) | VERIFIED. Secondary max is UNVERIFIED for Viking (REMS value used) |
| 2 | Curiosity REMS at Gale | Secondary max **Ls ≈ 57.5°**, primary min **Ls ≈ 152.8°**, primary max **Ls ≈ 255.1°**, secondary min **Ls ≈ 343.4°** (MY31–36). Annual mean **824–835 Pa** (at −4331 m), annual/semiannual harmonics **±50–54 / ±56–58 Pa**. Daily means run from about **700 to 925 Pa** | Ls and harmonics VERIFIED. The 700/925 Pa extremes are UNVERIFIED (consistent with the harmonics) |
| 3 | NASA career radiation limit | **600 mSv effective dose**, whole career, the same for all ages and sexes (3 % mean REID for cancer mortality). Requirement **[V1 4030]** | VERIFIED. The requirement number is NUANCE: checked against Rev C |
| 4 | Phoenix ice chunks | Exposed on **sol 20 (15 Jun 2008)**. Several were gone by **sol 24 (19 Jun)**, so **≈ 4 sols** | VERIFIED |
| 5 | Exposed-ice instability | Frost point **≈ 196 K** (−77 °C) for 10 pr-µm. ≈ 192 K at 5 pr-µm, ≈ 201 K at 20 pr-µm | COMPUTED (the literature range is ~196–200 K) |
| 6 | Cabin atmospheres | ISS: **14.7 psia (101.3 kPa), 21 % O2**. Exploration: **8.2 psia (56.5 kPa), 34 % O2**, a later EAWG recommendation. The final EAWG report is **NASA/TP-2010-216134** (Oct 2010), not "TP-2012", and it recommended **8.0 psia / 32 %** | VERIFIED. The citation is CORRECTED |
| 7 | Super Heavy engine ring | 3 + 10 gimballed engines and 20 fixed outer engines. **No official ring radius.** Twenty 1.3 m circles cannot fit inside a 9 m circle; the largest that fit are **1.22 m** on a ring of radius **3.89 m** | UNVERIFIED / COMPUTED |
| 8 | Mars pole, IAU 2015 | **Changed the model** (Kuchynka et al. 2014): α0 = 317.269202 − 0.10927547T + periodic terms, δ0 = 54.432516 − 0.05827105T + periodic terms. At J2000 this gives **317.68086°, 52.88643°**, versus 317.68143°, 52.88650° in IAU 2009 | VERIFIED (NAIF) / COMPUTED |
| 9 | Mean elevation vs MOLA areoid | Global mean topography is **−0.55 km**: the mean radius 3389.50 km sits 0.55 km below the mean areoid radius 3390.05 km. **5.1 mbar at Ls 0 at MOLA zero** is confirmed. If 636 Pa is the global-mean surface pressure, the pressure at MOLA zero is **≈ 585 Pa**. No source gives an annual mean (~5.6 mbar) at MOLA zero | COMPUTED / VERIFIED / UNVERIFIED |
| 10 | Vacuum exposure | Dogs collapse in **9–10 s**. **All survived < 120 s**; **120–180 s killed ≈ 15 % to > 80 %**. Chimpanzees survived **5–150 s** with no lasting effects | VERIFIED. The earlier "primate survival < 120 s" is CORRECTED to ≤ 150 s |

---

## 1. Viking Lander annual (seasonal) pressure cycle

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| Primary (absolute) minimum | **Ls ≈ 148–149°**: VL1 148.37° and 148.07°, VL2 149.12°. This is late southern winter, when the south seasonal cap stops growing | Tillman et al. 1993, *JGR* 98:10963. Tabulated in de la Torre Juárez et al. 2024, *JGR Planets* 129, e2023JE007810, Table 1 (NASA NTRS 20240000837) | https://ntrs.nasa.gov/api/citations/20240000837/downloads/GuzewichPressureSTI.pdf ; https://doi.org/10.1029/93JE01084 | VERIFIED. Text: the Viking estimates show "an earlier start at Ls ∼ 149°" than Gale. Hourdin et al. 1993: "The first deep minimum of pressure, near sol 100, occurs during southern winter" |
| Primary maximum | **Ls ≈ 260°**: VL1 260.38° and 262.75°, VL2 259.82°. This is southern summer, after the south cap sublimates | Same Table 1 | Same | VERIFIED, with a NUANCE. The table's ΔLs column (102.01°) would put VL1 at 250.4°, so the table contradicts itself. The text says "a later maximum is visible at Ls ∼ 260° in VL vs. REMS". Use 255–260° |
| Secondary minimum | **≈ Ls 340–345°**, late northern winter | Hourdin et al. 1993, *J. Atmos. Sci.* 50:3625: "The secondary minimum near sol 430 corresponds to the northern winter, much shorter and less cold than the southern winter" | https://www-mars.lmd.jussieu.fr/mars/jas93.pdf | COMPUTED: VL1 sol 430 ≈ 4 Oct 1977 ≈ Ls 343° (see the appendix). REMS gives Ls ≈ 343.4° (§2) |
| Secondary maximum | **≈ Ls 45–60°**, northern spring, after the north cap sublimates and before aphelion | REMS value: Ls ≈ 57.5° (§2) | — | UNVERIFIED for Viking. A search summary describes "a local maximum at about Ls 45" in the Viking record, but I could not open its source. The Hess et al. 1980 and Tillman et al. 1993 abstracts give no Ls values. Hourdin et al.: at VL1 the northern-spring maximum is lower than the perihelion maximum |
| VL1 pressure at primary min / max | **≈ 6.9 mbar** (min), **≈ 9.0 mbar** (max) | NSSDCA *Mars Fact Sheet*: "6.9 mb to 9 mb (Viking 1 Lander site)" | https://nssdc.gsfc.nasa.gov/planetary/factsheet/marsfact.html | VERIFIED (already in science-sources.md §2). Hourdin et al. Fig. 1 (Tillman & Guest 1987 data) shows the same range. The 9 mbar peak may include the 1977 dust-storm year |
| VL1 annual mean | **7.9 mbar**: "The two annual mean pressures are identical to 0.006 mbar out of 7.9 mbar" | Tillman et al. 1993, abstract (via Crossref) | https://doi.org/10.1029/93JE01084 | VERIFIED. Tillman's harmonic fit uses a mean plus the fundamental and four harmonics. The coefficients are in the paywalled paper |
| Relative amplitude | **min/mean ≈ 0.87, max/mean ≈ 1.14** (≈ −13 % / +14 %, ≈ 27 % peak to peak) | COMPUTED from 6.9, 9.0 and 7.9 mbar. NASA: "each year the atmosphere grows and shrinks by about 30 percent" (PIA16912) | https://science.nasa.gov/resource/seasonal-pressure-curve-peaks-at-gale-crater/ | COMPUTED, with NASA's ≈ 30 % VERIFIED. NUANCE: Hourdin et al. show that in the *global mean* the two maxima are "much more symmetric" than at VL1, so VL1 is not a perfect proxy for the global atmospheric mass |
| Hess et al. 1980 | Daily mean pressures at both landers for slightly more than a Mars year. The seasonal variation comes from CO2 exchange with the polar caps | Hess et al. 1980, *GRL* 7:197, abstract via Crossref | https://doi.org/10.1029/GL007i003p00197 | VERIFIED (abstract only). The abstract has no numbers |

## 2. Curiosity REMS pressure at Gale crater

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| Primary minimum | **Ls ≈ 152.8°** (MY32–36: 151.45–153.85°), late southern winter | de la Torre Juárez, Piqueux, Kass, Newman & Guzewich 2024, *JGR Planets* 129, e2023JE007810, Table 1 (NASA NTRS 20240000837) | https://ntrs.nasa.gov/api/citations/20240000837/downloads/GuzewichPressureSTI.pdf ; https://doi.org/10.1029/2023JE007810 | VERIFIED. Table mean 152.84° ± 0.61° |
| Primary maximum | **Ls ≈ 255.1°** (MY31–36: 254.05–256.15°), perihelion season | Same | Same | VERIFIED. Table mean 255.05° ± 0.72°. Viking peaked later, at ~260° |
| Secondary maximum | **Ls ≈ 57.5°** (MY32–36: 55.15–58.05°): "in NH spring shortly before aphelion, at Ls ∼ 58°" | Same | Same | VERIFIED. MY34 (55.15°) was the earliest |
| Secondary minimum | **Ls ≈ 343.4°** (MY31–35: 342.95–343.75°): "in late NP winter at Ls ∼ 344° before the NH spring equinox" | Same | Same | VERIFIED. NUANCE: the table's printed mean (344.08° ± 0.39°) does not match its own yearly values, which average 343.35°. Use ≈ 343–344° |
| Annual mean and harmonics | Annual mean **824, 834, 835, 835 Pa** in four Mars years, projected to z0 = −4331 m with H = 10 km. First (annual) harmonic **50–54 Pa**, semiannual **56–58 Pa** | Same, Fig. 2 caption | Same | VERIFIED. COMPUTED: peak to peak cannot exceed 2 × (54 + 58) ≈ 220 Pa |
| Annual range of daily means | **≈ 700 Pa (Ls ≈ 150°) to ≈ 925 Pa (Ls ≈ 250°)** near the landing elevation | Secondary: a search-engine summary of the REMS literature. The likely primary source, Ordóñez-Etxeberria et al. 2019 (*Icarus* 317:591), is paywalled and has no abstract in Crossref | https://doi.org/10.1016/j.icarus.2018.09.003 | UNVERIFIED, but consistent with the verified mean and harmonics (≈ 830 ± ~110 Pa). The Haberle et al. 2014 and Harri et al. 2014 abstracts give no values |
| Elevation drift | Curiosity climbed **≈ 740 m**, from −4500.97 m at landing to −3765.27 m on sol 3967 | Remote Sensing 17:368 (2025), abstract via Crossref | https://doi.org/10.3390/rs17030368 | NUANCE. COMPUTED: the climb alone lowers the measured pressure by ≈ 7 % (H ≈ 10.5 km). Compare years only after correcting for height, as de la Torre Juárez et al. do |
| Seasonal swing (NASA) | "each year the atmosphere grows and shrinks by about 30 percent". Curiosity landed near the annual minimum | NASA PIA16912 (8 Apr 2013) | https://science.nasa.gov/resource/seasonal-pressure-curve-peaks-at-gale-crater/ | VERIFIED |

## 3. NASA career radiation exposure limit

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| Career limit | **600 mSv effective dose** over a career. "The new standard is exposure based (600 mSv) based on a 3% mean Risk of Exposure-Induced Death (REID) for cancer mortality. The exposure limit is universal for males and females." | NASA OCHMO Standards Newsletter, Feb 2022, "NASA-STD-3001 Volume 1, Rev B and Volume 2, Rev C" | https://www.nasa.gov/wp-content/uploads/2023/03/ochmo-standards-newsletter-february-2022.pdf | VERIFIED. The newsletter says Rev B "has received agencywide approval". The limit was updated on National Academies input. Rev B also added short-term limits for solar particle events and limits for nuclear technologies |
| Requirement number | **[V1 4030]**, "Career Space Permissible Exposure Limits for Spaceflight Radiation": "total career effective radiation dose due to spaceflight radiation exposure shall be less than 600 mSv" | NASA-STD-3001 Vol. 1 **Rev C** and its Appendix D compliance matrix | https://www.nasa.gov/wp-content/uploads/2023/11/nasa-std-3001-vol-1-rev-c-with-signature.pdf ; https://www.nasa.gov/wp-content/uploads/2025/01/nasa-std-3001-vol-1-appendix-d.pdf | NUANCE. I confirmed the number for Rev C (which superseded Rev B) from a search-engine extract of these NASA PDFs, not by opening Rev B. Cite as "NASA-STD-3001 Vol. 1 Rev B (2022; carried into Rev C), [V1 4030]" |
| GCR shielding (context) | A *proposed* standard to keep GCR effective dose below **1.3 mSv/day in free space** and **0.8 mSv/day on planetary surfaces** | Same newsletter | Same | NUANCE: a proposal in 2022, not part of Rev B |

## 4. Phoenix: exposed ice in the "Dodo-Goldilocks" trench

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| Time to vanish | **≈ 4 sols.** The chunks were exposed when the arm enlarged the trench on **sol 20 (15 June 2008)**. "Several were gone when Phoenix looked at the trench early today, on Sol 24" (19 June 2008) | NASA/JPL news release, 19 June 2008, "Bright Chunks at Phoenix Lander's Mars Site Must Have Been Ice". Images PIA10910 ("Now You See It") and PIA10911 ("Now It's Gone") | https://www.jpl.nasa.gov/news/bright-chunks-at-phoenix-landers-mars-site-must-have-been-ice/ ; https://www.jpl.nasa.gov/images/pia10911-ice-on-mars-now-its-gone/ | VERIFIED. "Dice-size crumbs of bright material have vanished from inside a trench where they were photographed by NASA's Phoenix Mars Lander four days ago". NUANCE: "several" chunks were gone, not all, and they were dice-sized. Smith et al. 2009 (*Science* 325:58) was not opened |

## 5. Temperature above which exposed water ice is unstable

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| Frost point of Martian air | **≈ 196 K (−77 °C)** for the typical **10 pr-µm** column mixed through the lowest scale height. **≈ 192 K** at 5 pr-µm, **≈ 201 K** at 20 pr-µm | COMPUTED with the Murphy & Koop 2005 ice vapour-pressure formula (see the appendix) | https://doi.org/10.1256/qj.04.94 | COMPUTED. Vapour pressure over ice is 0.087 Pa at 196 K and 0.163 Pa at 200 K, roughly doubling every 4 K. Ice warmer than ~200 K therefore sublimates quickly. The result does not depend on surface pressure |
| Stability criterion | "Ground ice was found to be stable where the annual mean surface and subsurface temperatures were below the atmospheric frost point." | Mellon & Jakosky 1993, *JGR* 98:3345, abstract via Crossref | https://doi.org/10.1029/92JE02355 | VERIFIED (the concept). The abstract gives no number. The exact frost-point values in Schorghofer & Aharonson 2005 (*JGR* 110, E05003) and Hecht 2002 (*Icarus* 156:373) are UNVERIFIED because both papers are paywalled. The literature value is typically ~196–200 K |
| Game mapping | Exposed water ice above **~196–200 K** loses mass. In northern summer at Phoenix, dice-sized chunks vanished in ≈ 4 sols (§4) | — | — | NUANCE: stability is relative to local humidity. Frost forms where the surface is colder than the local frost point |

## 6. Spacecraft cabin atmospheres

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| ISS cabin | **14.7 psia (101.3 kPa), 21 % O2.** The table row reads "ISS/US 101.3 (14.7) [kPa (psia)], 21 [% O2]". The text says "the nominal ISS atmosphere of 14.7 psia and 21% oxygen" | NASA/TP-2010-216134 (EAWG final report) | https://www.nasa.gov/wp-content/uploads/2023/03/henninger-8.2-34-atm-tp216134-2010.pdf | VERIFIED. COMPUTED: ppO2 ≈ 21.3 kPa (≈ 160 mmHg) |
| EAWG final report (2010) | Landers: "For surface operations, the lunar and Mars landers should also operate at a nominal **8.0 psia, 32% oxygen**." Habitats: 8.0 psia and 7.6 psia, both 32 %. Transit: "The Mars transit vehicle should operate at nominal values of 14.7 psia, 21% oxygen and 10.2 …" (10.2 psia, 26.5 % O2 is the reduced-pressure mode) | *Recommendations for Exploration Spacecraft Internal Atmospheres: The Final Report of the NASA Exploration Atmospheres Working Group*, NASA/TP-2010-216134, JSC, **October 2010** | Same | CORRECTED citation: **TP-2010**-216134, not "TP-2012". This report recommended 8.0 / 32 %, not 8.2 / 34 %. Its trade space ("Point Y") spans 8–8.4 psia and 27.6–34 % O2 |
| 8.2 psia / 34 % O2 | "Multidisciplinary EAWG recommendation to adopt **8.2 psia / 34% O2** as a capability for future missions; conduct testing in microgravity." EVA prebreathe: **0:00–0:15** from 8.2 / 34, against 4:00 from 14.7 / 21 and about 2:40 from 10.2 / 26.5 | Garbino et al., panel "NASA's Exploration Atmospheres & EVA Strategies", AsMA, May 2022 (NASA NTRS 20220005716) | https://ntrs.nasa.gov/api/citations/20220005716/downloads/AsMA%202022%20-%20Garbino%20-%20Exploration%20Atmospheres%20v2.pdf | VERIFIED as NASA's current exploration atmosphere, a later refinement of the 2010 8.0 / 32 % point. COMPUTED: 8.2 psia = 56.5 kPa. ppO2 = 2.79 psia = 19.2 kPa (≈ 144 mmHg) |

## 7. Super Heavy: Raptor 3 nozzle size and the 20 outer engines

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| Engine layout | **33 Raptors: 13 gimballed in the centre (3 + 10), 20 around the perimeter.** "The outer ring contains the remaining 20 engines that do not have the ability to gimbal" | SpaceX *Starship* page. Everyday Astronaut, Flight 5 explainer | https://www.spacex.com/vehicles/starship/ ; https://everydayastronaut.com/starship-super-heavy-flight-5/ | VERIFIED (SpaceX). The ring structure is from a secondary source |
| Mounting | The outer 20 are "mounted on a ring attached to the first steel ring" (the aft skirt). The inner 13 are on "the thrust puck, a part of the aft dome" | Wikipedia, *SpaceX Super Heavy* (pointer only; I found no primary source) | https://en.wikipedia.org/wiki/SpaceX_Super_Heavy | UNVERIFIED (secondary) |
| Ring radius and nozzle fit | **No official ring radius or nozzle-exit diameter.** SpaceX's "1.3 m × 2.9 m" does not say whether 1.3 m is the exit diameter or the engine's overall width. COMPUTED: 20 touching circles of diameter d on one ring fit inside radius 4.5 m only if **d ≤ 1.22 m**, which puts the ring at **3.89 m**. With d = 1.3 m the ring radius must be ≥ 4.16 m, so the nozzle edges reach 4.81 m, which is **0.31 m outside** a 9 m cylinder | COMPUTED (see the appendix) | — | UNVERIFIED. Neither SpaceX nor NASA publishes this geometry. For the game, either put the outer ring at r ≈ 3.85–3.9 m with exits of about 1.2 m, which stays inside the 9 m outline, or let 1.3 m nozzles overhang slightly |

## 8. Mars north pole orientation (IAU WGCCRE)

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| IAU 2009 (Archinal et al. 2011) | α0 = 317.68143 − 0.1061 T, δ0 = 52.88650 − 0.0609 T | Archinal et al. 2011, *CMDA* 109:101 | https://aa.usno.navy.mil/downloads/reports/Archinaletal2011a.pdf | Values as given in the request (not re-opened here) |
| IAU 2015 (Archinal et al. 2018) | **The model changed.** α0 = **317.269202 − 0.10927547 T** + 0.000068 sin M1 + 0.000238 sin M2 + 0.000052 sin M3 + 0.000009 sin M4 + **0.419057 sin(79.398797° + 0.5042615° T)**. δ0 = **54.432516 − 0.05827105 T** + 0.000051 cos … + 0.000141 cos … + 0.000031 cos … + 0.000005 cos … + **1.591274 cos(166.325722° + 0.5042615° T)**. W = **176.049863 + 350.891982443297 d** + periodic terms. M1–M4 have periods of 1, ½, ⅓ and ¼ Mars year (e.g. M1 = 198.991226° + 19139.4819985° T) | NAIF generic PCK `pck00011.tpc` (BODY499_POLE_RA/DEC/PM, BODY499_NUT_PREC_*), built from Archinal et al. 2018, *CMDA* 130:22 | https://naif.jpl.nasa.gov/pub/naif/generic_kernels/pck/pck00011.tpc ; https://doi.org/10.1007/s10569-017-9805-5 | VERIFIED from NAIF's machine-readable copy. The Springer paper itself was not opened. The model follows Kuchynka et al. 2014 (Opportunity radio tracking), according to search results citing arXiv:2309.02220 |
| IAU 2015 value at J2000 | **α0 = 317.68086°, δ0 = 52.88643°** | COMPUTED from the PCK coefficients (appendix) | — | COMPUTED. Differs from IAU 2009 by −0.0006° (α) and −0.00006° (δ) at J2000, and by −0.0009° / −0.0005° in late 2026. Negligible for the game; IAU 2015 is the current model. A 2019 erratum exists (https://doi.org/10.1007/s10569-019-9925-1); I did not check whether it affects Mars |

## 9. Mean surface height relative to the MOLA areoid, and the pressure at MOLA zero

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| Global mean elevation | **−551 m**, the area-weighted (cos φ) mean of the MOLA 4 px/deg median topography relative to the areoid. Northern hemisphere **−2.18 km**, southern **+1.08 km**. **51.2 %** of the surface lies below 0 m | COMPUTED from NASA PDS MOLA MEGDR `megt90n000cb.img` (1440 × 720, MSB int16, metres) | https://pds-geosciences.wustl.edu/mgs/mgs-m-mola-5-megdr-l3-v1/mgsl_300x/meg004/megt90n000cb.img | COMPUTED. The unweighted grid mean (−722 m) is wrong because it over-counts polar pixels |
| Mean radius vs mean areoid | Mean planetary radius **3389.500 km**, matching NSSDCA's 3389.5 km. Mean areoid radius **3390.050 km**. The "mean radius" sphere is ≈ 0.55 km below the average areoid | COMPUTED from `megr90n000cb.img` and `mega90n000cb.img` (offset 3,396,000 m) | https://pds-geosciences.wustl.edu/mgs/mgs-m-mola-5-megdr-l3-v1/mgsl_300x/meg004/ | COMPUTED |
| Pressure at MOLA zero, Ls 0 | "The average pressure on the MOLA reference surface for Ls = 0 is approximately **5.1 mbars** and has been derived from occultation data obtained from the tracking of Viking, Mariner, and MGS spacecraft and interpolated with the aid of the Ames Mars GCM." Also: "The mean atmospheric pressure surface of 6.1 mbars that has been used in the past as a reference level for topography does not apply to the zero level of MOLA elevations." | Smith & Zuber 1999 (conference abstract, NTRS 19990115808) | https://ntrs.nasa.gov/citations/19990115808 | VERIFIED. The wording in science-sources.md §1 is correct |
| Relating NSSDCA's 636 Pa to MOLA zero | If 636 Pa is the **area-mean surface pressure**: p(0) = 636 / ⟨e^(−h/H)⟩ = 636 / 1.088 ≈ **585 Pa** (H = 11 km). If it is the pressure **at the mean elevation (−0.55 km)**: p(0) = 636 · e^(−0.55/11) ≈ **605 Pa**. Conversely, 510 Pa at MOLA zero implies a global-mean surface pressure of **≈ 555 Pa** at Ls 0 | COMPUTED (isothermal; using H = 10–11.1 km changes these by ≤ 1 %) | — | COMPUTED. NUANCE: the NSSDCA and Smith & Zuber anchors differ by ≈ 13 %, and NSSDCA does not define "at mean radius". Pick one anchor and document it in SCIENCE.md |
| Annual mean at MOLA zero (~5.6 mbar?) | **Not found** in any authoritative source | — | — | UNVERIFIED. Do not cite 5.6 mbar |

## 10. Unprotected exposure to near-vacuum: blackout and death

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| Time to collapse | Dogs "collapsed within **9 to 10 seconds** after decompression" to < 2 mmHg | Bancroft & Dunn 1965, *Experimental Animal Decompressions to a Near-Vacuum Environment* (NASA NTRS 19660005052) | https://ntrs.nasa.gov/citations/19660005052 | VERIFIED. Matches the CliFF figure of 9–11 s |
| Survival, dogs | "All dogs exposed for less than **120 seconds** survived … Exposures of 120 to 180 seconds resulted in approximately **15% to more than 80% fatalities**, respectively." 126 dogs; exposures of 5–180 s; recompressed in 5 or 30 s | Same | https://ntrs.nasa.gov/api/citations/19660005052/downloads/19660005052.pdf | VERIFIED. Recovery required the heart to still be beating at recompression; "otherwise, death was inevitable" |
| Survival, chimpanzees | "Eight chimpanzees … were decompressed … to less than 2 mm Hg in 0.8 seconds and remained at this altitude from **5 to 150 seconds**." "All subjects survived in good health and no lasting effects of rapid decompression to a near vacuum could be detected." | Koestler 1965, *The Effect on the Chimpanzee of Rapid Decompression to a Near Vacuum*, NASA contractor report (6571st Aeromedical Research Laboratory, Holloman AFB), NTRS 19650027167 | https://ntrs.nasa.gov/citations/19650027167 | CORRECTED: the earlier notes give primate survival as "< 120 s", but this NASA report shows full survival up to **150 s**. The popular "3.5 minutes" chimpanzee figure is not in this report and is UNVERIFIED |
| Human time to death | There are no direct human data. The animal data and NASA's "one or two minutes" (Ask an Astrophysicist) agree: probably survivable if repressurised within ≈ 90–120 s, and increasingly fatal beyond ~2–3 min | Synthesis of the above | — | NUANCE. A game model of blackout at ~10 s and death at ~120 s without repressurisation is defensible |

---

## Appendix: computations

**MOLA global means (§9).** The 4 px/deg MEGDR grids have line centres at latitude 90 − (i + 0.5)/4. I weighted each
pixel by cos φ. Results: ⟨topography⟩ = −551.5 m; ⟨radius⟩ = 3,389,500 m; ⟨areoid⟩ = 3,390,050 m. The grid's minimum
and maximum are −8,068 and +21,134 m. The pressure factor ⟨e^(−h/H)⟩ is 1.1007 for H = 10 km, 1.0876 for H = 11 km,
and 1.0864 for H = 11.1 km.

**Frost point (§5).** A column of W pr-µm has a water mass of m = W × 10⁻³ kg/m². If the vapour is well mixed, its
partial pressure is e = m·g·(M_air/M_H2O) = W × 0.00893 Pa (g = 3.71 m/s², M_air = 43.34, M_H2O = 18.015), which does
not depend on surface pressure. So 10 pr-µm gives e = 0.089 Pa (≈ 146 ppmv at 610 Pa). Solving
ln p_ice = 9.550426 − 5723.265/T + 3.53068 ln T − 0.00728332 T (Murphy & Koop 2005) gives T = 196.2 K. The same
method gives 191.9 K for 5 pr-µm and 200.6 K for 20 pr-µm.

**Engine ring (§7).** For n = 20 equal circles of diameter d whose centres lie on a ring of radius R, the chord between
neighbours, 2R·sin(π/20) = 0.3129 R, must be at least d. The circles stay inside the skin if R + d/2 ≤ 4.5 m. The
largest d that satisfies both is d_max = 9·sin 9°/(1 + sin 9°) = 1.2175 m, at R = 3.89 m. For d = 1.3 m:
R ≥ 4.155 m, so the outer edges are at 4.805 m.

**IAU 2015 pole at J2000 (§8).** At T = 0, α0 = 317.269202 + 0.419057·sin 79.398797° − 0.00025 = 317.68086°, and
δ0 = 54.432516 + 1.591274·cos 166.325722° + 0.00009 = 52.88643°. I checked this by evaluating every BODY499 term in
`pck00011.tpc` in code.

**Viking sol 430 → Ls (§1).** VL1 landed on 20 July 1976 (Ls ≈ 97°). 430 sols × 1.0275 d = 441.8 d, which is about
4 October 1977. Mars Year 13 began (Ls 0) on about 4–5 November 1977, so the secondary minimum is ≈ 31 days before
Ls 0. Mars's orbital rate near Ls 340° is ≈ 0.53°/day, which gives Ls ≈ 343°.
