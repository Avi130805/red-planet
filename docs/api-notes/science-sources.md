# Science fact-check and source list

Fact-check of the numbers proposed for *Red Planet: Starship to Mars*, done on 2026-10-04. Each value was checked
against primary or authoritative sources: NASA/NSSDCA, JPL, NASA GISS, PDS, USGS, ESA, DLR, peer-reviewed papers, and
SpaceX's own website and flight pages. Wikipedia is used only to point to a primary source, and is marked as such.

**Status tags used in the tables**

- **VERIFIED**: the proposed value matches the source.
- **CORRECTED**: the proposed value is wrong or oversimplified. Use the verified value.
- **NUANCE**: the value is right, but a caveat matters for the game.
- **COMPUTED**: derived by me from verified constants. The formulas are in the appendix.
- **UNVERIFIED**: I could not confirm it from an authoritative source. The note says what I did find.

Paywalled papers (Wiley/AGU, Elsevier, Springer, Science full text) could not be opened from this machine. For those
papers I quote the abstract, which I checked through PubMed or Crossref, or a NASA or peer-reviewed secondary source
that quotes the number. The notes column says when this applies.

---

## Summary of corrections (read this first)

| # | Proposed | Use instead | Why |
|---|---|---|---|
| 1 | Surface gravity 3.721 m/s² | ≈3.72 m/s² effective mean is fine. NSSDCA lists 3.73 (mean, GM/R²), 3.69 (equator) and 3.73 (pole) | 0.379 g is right |
| 1 | "MOLA reference sphere 3396 km" | The MOLA zero level is an **equipotential areoid** whose mean *equatorial* radius is 3,396,000 m. 3396 km is a true sphere only for map projection and radius offsets | PDS MOLA SIS |
| 2 | Mean surface pressure ~610 Pa | NSSDCA: **636 Pa at mean radius** (400–870 Pa seasonally). 6.1 mbar was the *pre-MOLA* datum. At the MOLA zero level the mean pressure is ≈510 Pa at Ls 0 (Smith & Zuber 1999) | Datum confusion |
| 2 | Surface density ~0.020 kg/m³ | **≈0.016 ± 0.006 kg/m³** (NSSDCA, 2025) | 0.020 is the old value |
| 2 | Scale height 11.1 km | **11.0 km** (NSSDCA). Really 10–11 km depending on T | |
| 5 | Ground/air maximum +35 °C | NASA gives **+20 °C** (Mars Facts) to **+27 °C** (JPL press kit). Curiosity REMS ground maximum is ≈ +17 °C (≈290 K) | The +35 °C claim is untraceable |
| 6 | "Perihelion near Ls 251° (southern summer)" | Ls 251° is **late southern spring**, about 19° of Ls before the southern summer solstice (Ls 270°) | |
| 6 | (NASA's Mars Facts page says "669.6 sols") | **668.6 sols** (668.5921 tropical) is correct. NASA's page has a typo | Mars24 |
| 7 | Irradiance at perihelion/aphelion 717/493 W/m² | **713 / 490 W/m²** (mean 586, orbit-averaged 589) with today's TSI of 1361 W/m². 717/493 come from Appelbaum & Flood 1989, who used S = 1371 W/m² | Old solar constant |
| 9 | Phobos 0.21° × 0.14° at zenith | **≈0.22° × 0.17°** (13.1′ × 10.5′) at zenith, ≈0.15° × 0.12° at the horizon | Computed from NSSDCA radii |
| 9 | Not visible above 70.4° | The geometric limit for an equatorial orbit is **68.8°**. 70.4° is the extreme case: Phobos at apoapsis, plus its 1.08° inclination, measured in planetographic latitude | Computed |
| 10 | Deimos up ~2.7 sols | **≈59.6 h = 2.42 sols (2.48 Earth days)** from the equator. The 2.7-day figure ignores parallax (it is half the 131.4 h synodic period) | Computed |
| 11 | Earth max elongation ~47° | **≈41° typical, 36–47° range**. 47.4° occurs only with Mars at perihelion and Earth at aphelion | Computed |
| 13 | Olympus Mons 21.9 km above datum | Summit **≈21.1–21.3 km above the MOLA datum** (21,229 m, Smith et al. 2001; 21,290 m, USGS 2021). **21.9 km is the relief** in Plescia 2004 | |
| 13 | Olympus basal escarpment 6–8 km | **Up to ≈10 km** high in places, ≈7 km on the east flank (Weller et al. 2014) | |
| 13 | Valles Marineris up to 200 km wide, 7 km deep | Individual chasmata are ~200 km wide, but the system is **≈600 km at its widest** (NASA). Depth is **up to ≈8–10 km** (NASA 9.3 km; ESA 10 km). "7 km" appears in one ESA article but is not the maximum | |
| 13 | Dichotomy 5–6 km | **≈5 km** on average (MOLA team) | |
| 14 | "Gypsum veins" (Nachon 2014) | **Calcium-sulfate veins**, hydrated "at a level expected for gypsum and bassanite", with anhydrite locally | Abstract |
| 21 | Raptor 3 ≈280 tf | SpaceX's current figure is **250 tf sea-level and 275 tf vacuum** (May 2026, "Introducing Starship V3"). 280 tf was the August 2024 figure (as reported by secondary sources) | Superseded |
| 21 | Booster grid fins "3 or 4" | **4 on V1/V2 boosters, 3 on V3** (each 50% larger) | SpaceX V3 update |
| 21 | V3 Super Heavy 72.3 m, stack ~124 m | SpaceX's site lists **72 m booster, 52 m ship, 124 m stack, 1,600 t ship and 3,650 t booster propellant, 8,240 tf liftoff thrust** | |
| 22 | MECO ~T+2:40 | V1/V2: **T+2:35–2:46** (Flight 5 2:35, hot staging 2:40). V3: **T+2:18–2:22** | SpaceX timelines |
| 23 | Earth return entry ~12 km/s | **≈11.5 km/s** for a minimum-energy return (COMPUTED; Kingdon 2025 has one example at 11.2 km/s). **12–13 km/s** for fast returns | Computed / Kingdon 2025 |

**UNVERIFIED** (details in each section): the GRCop-42 chamber alloy; Raptor 3 chamber pressure (330–350 bar);
hot-staging speed and altitude from webcast telemetry; belly-flop terminal velocity (no official SpaceX number); the
official number of tanker flights for a Mars ship; the exact Franz 2017 CO value (0.058 %); Tharsis Montes heights from
Plescia's table (paywalled); +35 °C maximum temperature; Earth's peak brightness from Mars (computed only); the
sea-level/vacuum split of Raptor Isp; V1 booster propellant (3,250 vs 3,400 t); the 18,000-tile count (secondary only).

---

# Part A: Mars

## 1. Gravity, radius, MOLA datum, circumference

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| Surface gravity | **3.73 m/s² mean** (GM/R²), **3.69 equator**, **3.73 pole**. Ratio to Earth 0.380 / 0.377 / 0.379 | NASA NSSDCA Mars Fact Sheet (D. Williams, updated 19 May 2025) | https://nssdc.gsfc.nasa.gov/planetary/factsheet/marsfact.html | NUANCE. 3.721 m/s² (0.3794 g) is an acceptable effective mean that includes rotation. COMPUTED: GM/R² at R = 3389.5 km gives 3.728. Centrifugal term at the equator is 0.017 m/s². Gameplay: 3.72 m/s² (0.38 g) |
| Volumetric mean radius | **3389.5 km**. Equatorial 3396.2 km, polar 3376.2 km, flattening 0.00589 | NSSDCA Mars Fact Sheet | https://nssdc.gsfc.nasa.gov/planetary/factsheet/marsfact.html | VERIFIED. MOLA gives 3,389,508 m (Smith & Zuber 1999) |
| MOLA zero level | "The areoid radius lies on an **equipotential surface whose mean radius at the equator is 3396000 meters**." "Topography is the planetary radius minus the areoid radius." Gridded products use a 3396.0 km sphere for map projection and a 3,396,000 m offset for radius | NASA PDS, MOLA EGDR Software Interface Spec. MEGDR label MEGT90N000CB | https://pds.nasa.gov/data/mgs-m-mola-5-iegdr-l3-v1.0/mgsl_2044/document/egdrsis.htm ; https://pds-geosciences.wustl.edu/mgs/mgs-m-mola-5-megdr-l3-v1/mgsl_300x/meg004/megt90n000cb.lbl | CORRECTED wording. Elevations are relative to the areoid, not to a 3396 km sphere. The 1/4° MEGDR median grid spans −8,068 m to +21,134 m |
| Pre-MOLA datum | "The mean atmospheric pressure surface of 6.1 mbars that has been used in the past as a reference level for topography does not apply to the zero level of MOLA elevations." Mean pressure at the MOLA zero level is ≈5.1 mbar at Ls 0 | Smith & Zuber 1999, *The Relationship of the MOLA Topography of Mars to the Mean Atmospheric Pressure* (NASA NTRS 19990115808) | https://ntrs.nasa.gov/citations/19990115808 | Important for any p(z) model anchored to "610 Pa at 0 km" |
| Equatorial circumference | **21,339 km** (2π × 3396.2 km). The mean-radius circumference is 21,297 km | COMPUTED from NSSDCA radii | (appendix) | VERIFIED as arithmetic |

## 2. Atmosphere

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| Mean surface pressure | **6.36 mbar (636 Pa) at mean radius**, varying 4.0–8.7 mbar with season. 6.9–9 mbar at the Viking 1 site | NSSDCA Mars Fact Sheet (2025) | https://nssdc.gsfc.nasa.gov/planetary/factsheet/marsfact.html | NUANCE. "~610 Pa" is the old 6.1 mbar datum (see §1). Use ≈600–640 Pa as a "typical" global value and remember the ±25–30 % seasonal CO2 cycle |
| Surface density | **~0.016 ± 0.006 kg/m³** | NSSDCA Mars Fact Sheet (2025) | same | CORRECTED from 0.020. COMPUTED: ρ = pM/RT gives 0.015 kg/m³ at 610 Pa and 210 K, and 0.018 at 700 Pa and 200 K |
| Scale height | **11.0 km** | NSSDCA | same | CORRECTED from 11.1. COMPUTED: H = RT/(Mg) gives 10.3 km at 200 K, 10.8 km at 210 K and 11.0 km at 214 K |
| Mean molecular weight | 43.49 g/mol (NSSDCA). ≈43.4 g/mol from the SAM composition (COMPUTED) | NSSDCA | same | |
| Composition (SAM, Curiosity) | **CO2 0.951, N2 0.0259, ⁴⁰Ar 0.0194, O2 0.00161** (annual means) | Vasavada 2022, *Space Sci. Rev.* 218:14, citing Trainer et al. 2019 and Franz et al. 2017 | https://doi.org/10.1007/s11214-022-00882-7 ; https://pmc.ncbi.nlm.nih.gov/articles/PMC8981195/ | VERIFIED for CO2, N2, Ar and O2. Franz et al. 2017, *PSS* 138:44, https://doi.org/10.1016/j.pss.2017.01.014, is paywalled; only its abstract was read |
| CO | NSSDCA lists **0.06 %**. Individual SAM runs give 0.042–0.11 % (mean of 18 runs ≈0.076 %) | NSSDCA; Lo et al. 2024 *PSJ* 5:65, Table A1 (Trainer 2019 data) | https://iopscience.iop.org/article/10.3847/PSJ/ad251b | UNVERIFIED for the exact 0.058 % value. It is consistent with NSSDCA to the stated precision |
| Seasonal variability | Single SAM runs: CO2 0.945–0.954, Ar 1.85–2.26 %, N2 2.50–2.93 %, O2 0.128–0.216 %. O2 rises by as much as 30 % in spring and summer | Lo et al. 2024, Table A1. NASA Goddard release (Steigerwald, 12 Nov 2019) | https://iopscience.iop.org/article/10.3847/PSJ/ad251b ; https://www.nasa.gov/feature/goddard/2019/with-mars-methane-mystery-unsolved-curiosity-serves-scientists-a-new-one-oxygen | The NASA release rounds to 95 / 2.6 / 1.9 / 0.16 / 0.06 % |
| Pressure at the Hellas floor | **≈1.15–1.25 kPa**. ESA: "in the deepest parts of the basin, the atmospheric pressure is about 89% higher than at the surface" | ESA, *Mars Deep down* (18 Aug 2014) | https://www.esa.int/Science_Exploration/Space_Science/Mars_Express/Mars_Deep_down | VERIFIED (~1.2 kPa). COMPUTED: 610·exp(7.15/H) gives 1.16–1.25 kPa for H = 10–11 km. Up to ~1.3–1.4 kPa at the −8.2 km low point |
| Pressure at the Olympus Mons summit | **≈70–90 Pa**. Wikipedia: "typical atmospheric pressure at the top of Olympus Mons is 72 pascals" | Wikipedia (pointer, citing MGS Radio Science standard T–p profiles, Stanford). My exponential estimate | https://en.wikipedia.org/wiki/Olympus_Mons | NUANCE. No direct measurement exists. 610·exp(−21.23/H) gives 73 Pa (H = 10 km) to 90 Pa (H = 11.1 km). ~70 Pa is reasonable for a cold column |

## 3. Phase points

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| Water triple point | **T_t = 273.16 K, p_t = 611.657 Pa** | IAPWS R14-08(2011), *Revised Release on the Pressure along the Melting and Sublimation Curves of Ordinary Water Substance* | https://iapws.org/technical-guidance/release/MeltSub | VERIFIED. Since the 2019 SI redefinition, 273.16 K is a measured value (uncertainty ~0.1 mK), no longer exact by definition |
| CO2 frost point at Mars surface pressure | **147.9 K (−125.3 °C) at 610 Pa**. 147.5 K at the CO2 partial pressure (580 Pa). 148.2 K at 636 Pa | Fanale et al. 1982 relation T_c = −3167.8/[ln(0.01p) − 23.23], as used by Forget et al. 2013 (*Icarus* 222:81–99) | https://doi.org/10.1016/j.icarus.2012.10.019 ; https://arxiv.org/abs/1210.4216 | VERIFIED (≈148 K / −125 °C). The frost point varies with elevation: ≈152–153 K (−120 °C) at the Hellas floor and ≈134–136 K (−138 °C) at the Olympus Mons summit (COMPUTED) |

## 4. Human exposure to near-vacuum

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| Armstrong limit (ebullism threshold) | **47 mmHg = 0.91 psia ≈ 6.3 kPa**, the vapour pressure of water at 37 °C. Reached at **19,200 m (63,000 ft)** | NASA Clinical Finding Form (CliFF) ICL37 *Ebullism* (2023) | https://www.nasa.gov/wp-content/uploads/2026/08/ebullism-cliff-2023.pdf | VERIFIED. COMPUTED: Buck equation at 37 °C gives 6.28 kPa (47.1 mmHg). Mars's surface pressure (≈0.6 kPa) is about 1/10 of this, so an unsuited human on Mars suffers ebullism |
| Time of useful consciousness | "Hypoxia … results in loss of consciousness in **9–11 seconds**" (NASA CliFF). In the 1965 NASA Manned Spacecraft Center chamber accident (<1 psi) the subject "remained conscious for about **14 seconds**" | NASA CliFF ICL37. NASA *Imagine the Universe*, Ask an Astrophysicist #970411a | https://www.nasa.gov/wp-content/uploads/2026/08/ebullism-cliff-2023.pdf ; https://imagine.gsfc.nasa.gov/ask_astro/space_travel.html | VERIFIED (9–15 s range) |
| Survival timeline | "Exposure … for half a minute or so is unlikely to produce permanent injury … After perhaps **one or two minutes**, you're dying." Non-human primates recovered within ~4 h after exposures **<120 s** (Koestler 1965, NASA CR-329; Rumbaugh 1965) | NASA Ask an Astrophysicist. NASA CliFF ICL37 | same | VERIFIED. Gameplay: blackout at ~10–15 s, death without repressurisation by ~90–120 s |

## 5. Temperatures

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| Global mean | NSSDCA: **~214 K (−59 °C)**, black-body 209.8 K. NASA: "median surface temperature on Mars is **−65 °C**". JPL InSight press kit: average **−53 °C** | NSSDCA. NASA *Temperatures Across Our Solar System*. JPL InSight *Mars at a Glance* | https://nssdc.gsfc.nasa.gov/planetary/factsheet/marsfact.html ; https://science.nasa.gov/solar-system/temperatures-across-our-solar-system/ ; https://www.jpl.nasa.gov/news/press_kits/insight/landing/facts/mars-at-a-glance/ | NUANCE. −63 °C (210 K) is inside NASA's range of −53 to −65 °C |
| Extremes | NASA: "as high as **20 °C** or as low as about **−153 °C**". JPL press kit: "**−128 °C** during polar night to **27 °C**" | NASA *Mars: Facts*. JPL InSight press kit | https://science.nasa.gov/mars/facts/ ; (above) | CORRECTED. Use +20 to +27 °C as the maximum, not +35 °C (UNVERIFIED: only Wikipedia's "Climate of Mars", no primary source). Wherever CO2 ice is present, the polar surface is held near the frost point (≈145–148 K, −125 to −128 °C). −140 °C is reasonable as an extreme |
| Air vs ground at the equator | "Spring at your feet (**24 °C**) and winter at your head (**0 °C**)" at the equator at noon | NASA *Mars: Facts* | https://science.nasa.gov/mars/facts/ | Strong vertical gradient. Good flavour text |
| Curiosity REMS at Gale (typical) | Before the 2018 storm (late southern winter and early spring): ground max **~286 K (+13 °C)**, min **~187 K (−86 °C)**, range **~94 K**. Air max **~276 K (+3 °C)**, min **~202 K (−71 °C)**, range **~71 K**. Diurnal-mean air ~231 K | Viúdez-Moreiras et al. 2019, *JGR Planets* 124:1899 (MY34 storm and REMS) | https://doi.org/10.1029/2019JE005985 ; https://pmc.ncbi.nlm.nih.gov/articles/PMC6750032/ | VERIFIED. The storm compressed the ranges to ~38 K (ground) and ~36 K (air) |
| REMS annual extremes | Ground up to **~290 K (+17 °C)**. Lows **~180 K** (≈ −93 °C) | Rivera-Valentín et al. 2018, *JGR Planets* 123:1156, citing Hamilton et al. 2014 | https://doi.org/10.1002/2018JE005558 ; https://arxiv.org/abs/1811.00862 | Perseverance ground temperatures range **−93 to +17 °C** (NASA temperatures page) |

## 6. Day, year, orbit, seasons

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| Sol | **24 h 39 m 35.244 s** | NASA GISS Mars24, *Technical Notes* (Allison & Schmunk) | https://www.giss.nasa.gov/tools/mars24/help/notes.html | VERIFIED |
| Sidereal day | **24 h 37 m 22.663 s** (NSSDCA 24.6229 h) | Mars24 notes. NSSDCA | same | VERIFIED |
| Mars year | Sidereal **686.980 d** (NSSDCA). Tropical **686.9725 d = 668.5921 sols**. Sidereal 668.5991 sols | Mars24 notes. NSSDCA | same | VERIFIED. NASA's *Mars: Facts* page says "669.6 sols", which is a typo |
| Obliquity | **25.19°** | NSSDCA | https://nssdc.gsfc.nasa.gov/planetary/factsheet/marsfact.html | VERIFIED |
| Eccentricity | **0.0934** (J2000 0.09341233). The table rounds it to 0.0935 | NSSDCA | same | VERIFIED |
| Ls definition | Ls = 0°, 90°, 180°, 270° are the **northern** vernal equinox, summer solstice, autumnal equinox and winter solstice | Mars24 notes | https://www.giss.nasa.gov/tools/mars24/help/notes.html | VERIFIED |
| Season lengths | N spring 194 sols, N summer 178, N autumn 142, N winter 154 | NASA *Mars: Facts* | https://science.nasa.gov/mars/facts/ | Useful for the in-game calendar |
| Perihelion | **Ls_p = 251.000° + 0.0064891° × (year − 2000)**, giving ≈251.2° in 2026. It "indicates a near alignment of the planet's closest approach … with its [northern] winter solstice season, as related to the occasional onset of global dust storms" | Mars24 notes and algorithm page | https://www.giss.nasa.gov/tools/mars24/help/algorithm.html | NUANCE. Ls 251° is late southern spring. Southern summer starts at Ls 270° |
| Dust-storm season | Dusty season ≈ **Ls 180–360°** (southern spring and summer). Wolkenberg et al. use Ls ~135–360°. Global storms average one every **3–6 Mars years**. The MY25 and MY34 storms started at Ls ~185°; MY28 at Ls ~265° | Wolkenberg et al. 2020, *JGR Planets* (NASA NTRS copy) | https://ntrs.nasa.gov/api/citations/20210012819/downloads/21-56.pdf | VERIFIED |

## 7. Sun as seen from Mars

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| Apparent diameter | **0.350° (21.0′) mean. 0.386° (23.2′) at perihelion. 0.320° (19.2′) at aphelion.** The Sun from Earth is 0.533° | COMPUTED: 2·atan(R☉/d), R☉ = 695,700 km, d from NSSDCA | (appendix) | VERIFIED (~0.35°) |
| Solar irradiance | **586.2 W/m²** at the semi-major axis (NSSDCA). Orbit-averaged 589. **713 at perihelion, 490 at aphelion** | NSSDCA. COMPUTED with TSI 1361 W/m² | https://nssdc.gsfc.nasa.gov/planetary/factsheet/marsfact.html | CORRECTED. 717/493 come from Appelbaum & Flood 1989, NASA TM-102299, which assumed S = 1371 W/m² ("1371/1.52369² = 590 W/m²"): https://ntrs.nasa.gov/citations/19890018252 |

## 8. Sky colour

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| Dust particle size | Cross-section-weighted mean radius **1.47 ± 0.21 µm (Gusev)** and **1.52 ± 0.18 µm (Meridiani)**. Visible τ ≈ 0.9. Dust scale height 11.56 ± 0.62 km | Lemmon et al. 2004, *Science* 306:1753 (abstract via PubMed) | https://doi.org/10.1126/science.1104474 | VERIFIED |
| Daytime sky | "The sky would be **hazy and red** because of suspended dust" | NASA *Mars: Facts* | https://science.nasa.gov/mars/facts/ | Butterscotch or tan by day |
| Blue sunset | Curiosity, sol 956 (**15 April 2015**), Mastcam left eye, PIA19400. "Fine particles … permit blue light to penetrate the atmosphere more efficiently". Blue stays "closer to sun's part of the sky" while red and yellow scatter more widely (forward scattering) | NASA/JPL Photojournal PIA19400 | https://www.jpl.nasa.gov/images/pia19400-sunset-in-mars-gale-crater/ | VERIFIED. Render a blue halo only within ~10–20° of the Sun at low Sun elevation |

## 9. Phobos

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| Orbital period | **0.31891 d = 7 h 39.2 m** | NSSDCA. ESA ("every 7 hours 39 minutes") | https://nssdc.gsfc.nasa.gov/planetary/factsheet/marsfact.html ; https://sci.esa.int/web/mars-express/-/31031-phobos | VERIFIED |
| Semi-major axis | **9,375 km** (JPL SSD 2025, MAR099 ephemeris). 9,378 km (NSSDCA). 9,375 km and "5989 km above the surface" (ESA) | JPL SSD *Planetary Satellite Mean Elements* (Brozović, Jacobson & Park 2025) | https://ssd.jpl.nasa.gov/sats/elem/ | VERIFIED (9376 km is within 1–2 km). e = 0.015, i = 1.1° |
| Mean radius | **11.08 ± 0.04 km**. Triaxial radii 13.0 × 11.4 × 9.1 km | JPL SSD *Physical Parameters*. NSSDCA | https://ssd.jpl.nasa.gov/sats/phys_par/ | VERIFIED |
| Angular size from the equator | **≈0.22° × 0.17° (13.1′ × 10.5′) at zenith** (distance ≈5,980 km, seen end-on to the long axis). **≈0.15° × 0.12° at the horizon** (≈8,740 km) | COMPUTED from the radii above | (appendix) | CORRECTED (the minor axis is 0.17°, not 0.14°). Roughly a third of the full Moon's diameter |
| Direction | "Phobos **rises in the west and sets in the east** as seen from Mars" | ESA Mars Express, Phobos page. NASA Hubble time-lapse ("Rising in the Martian west") | https://sci.esa.int/web/mars-express/-/31031-phobos ; https://science.nasa.gov/resource/time-lapse-video-of-phobos-in-orbit-around-mars-annotated-and-smoothed/ | VERIFIED |
| Synodic period relative to the surface | **11.11 h**: about 2.22 transits per sol, so it rises twice and occasionally three times | COMPUTED: 1/(1/7.6538 h − 1/24.6229 h) | (appendix) | VERIFIED |
| Time above the horizon (equator) | **≈4 h 15 m (4.24 h)**: a visible arc of 137.5° | COMPUTED: 2·acos(R/a)/360 × 11.11 h | (appendix) | VERIFIED |
| Visibility latitude | **68.8°** geometric limit (planetocentric, equatorial orbit). **≈69.8–70.4°** after adding the 1.08° inclination, apoapsis distance and planetographic latitude | COMPUTED | (appendix) | NUANCE. 70.4° is the extreme value. In-game: fade Phobos out between 69° and 70.5° |
| Brightness | V(1,0) = +11.8 (NSSDCA). Full phase at zenith gives m ≈ −9 (COMPUTED) | NSSDCA | same | COMPUTED only |

## 10. Deimos

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| Orbital period | **1.26244 d = 30.30 h (30 h 17.9 m)** | NSSDCA | https://nssdc.gsfc.nasa.gov/planetary/factsheet/marsfact.html | VERIFIED ("30 h 18 m") |
| Semi-major axis | **23,457 km** (JPL 2025). 23,459 km (NSSDCA) | JPL SSD mean elements | https://ssd.jpl.nasa.gov/sats/elem/ | NUANCE. 23,463 km is 4–6 km high. Use 23,458 ± 2 km |
| Mean radius | **6.2 ± 0.24 km**. Radii 7.8 × 6.0 × 5.1 km | JPL SSD physical parameters | https://ssd.jpl.nasa.gov/sats/phys_par/ | |
| Angular size | **≈2.1′ × 1.75′ (0.034° × 0.029°) at zenith**. Star-like to the naked eye | COMPUTED | (appendix) | VERIFIED (~0.03°). Wikipedia gives "no more than 2.5′" |
| Direction | Rises in the **east**, sets in the west, because its 30.3 h period exceeds the 24.6 h sidereal rotation | Kepler geometry. Wikipedia (pointer) | https://en.wikipedia.org/wiki/Deimos_(moon) | VERIFIED |
| Synodic period relative to the surface | **131.4 h = 5.33 sols** | COMPUTED: 1/(1/24.6229 − 1/30.2986) | (appendix) | VERIFIED (~131 h) |
| Time above the horizon (equator) | **≈59.6 h = 2.42 sols (2.48 Earth days)**: a visible arc of 163.4° | COMPUTED | (appendix) | CORRECTED from ~2.7 sols. "2.7 days" (Wikipedia) is half the synodic period, which ignores the observer's parallax |

## 11. Earth seen from Mars

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| Greatest elongation | **≈41° typical**. Range **36–47°**: 47.4° with Mars at perihelion and Earth at aphelion, 36.2° in the opposite case | COMPUTED: asin(r_E/r_M) | (appendix) | CORRECTED. ~47° is the maximum, not the typical value. Earth is a morning or evening "star" |
| Peak brightness | **≈ −2.5 mag** with Mars at mean distance. Up to ≈ −2.9 with Mars near perihelion | COMPUTED: Lambert-sphere phase law, V(1,0) = −3.99 (NSSDCA) | (appendix) | UNVERIFIED by observation. The order of magnitude is right |
| Earth–Moon image | Curiosity, sol 529 (**31 Jan 2014**), Mastcam left eye, ~80 min after sunset, Earth–Mars ≈160 million km. "A human observer … could easily see Earth and the moon as two distinct, bright 'evening stars'" | NASA/JPL PIA17936 *Bright 'Evening Star' Seen from Mars is Earth* | https://www.jpl.nasa.gov/images/pia17936-bright-evening-star-seen-from-mars-is-earth/ | VERIFIED |
| Earth–Mars distance | **54.6 to 401.4 million km** | NSSDCA | https://nssdc.gsfc.nasa.gov/planetary/factsheet/marsfact.html | VERIFIED |
| One-way light time | **3.0 to 22.3 min** | COMPUTED: d/c | (appendix) | VERIFIED |

## 12. Launch windows and transfers

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| Synodic period | **779.94 d** (≈25.6 months). JPL: "about once every **26 months**" | NSSDCA. JPL Mars 2020 launch press kit | https://nssdc.gsfc.nasa.gov/planetary/factsheet/marsfact.html ; https://www.jpl.nasa.gov/news/press_kits/mars_2020/launch/mission/ | VERIFIED |
| Hohmann transfer time | **258.9 d** (circular, coplanar orbits) | COMPUTED: π√(a³/μ☉), a = 1.26183 AU | (appendix) | VERIFIED (~259 d) |
| Typical transfers | Perseverance: **203 days**, about 471 million km (launched 30 Jul 2020, landed 18 Feb 2021). MSL: **253-day** cruise | JPL Mars 2020 landing press kit. Zeitlin et al. 2013 abstract | https://www.jpl.nasa.gov/news/press_kits/mars_2020/landing/mission/ ; https://doi.org/10.1126/science.1235989 | VERIFIED (6–9 months). Kingdon 2025 shows 90–104 day Starship transits are feasible (see §23) |

## 13. Geology and topography

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| Total relief | "Full range of topography on Mars is about **30 km**" | NASA GSFC MOLA release (D. Smith) | https://science.gsfc.nasa.gov/attic/mola/topography.html | VERIFIED |
| Hemispheric dichotomy | The Southern Hemisphere "sits, on average, about **5 km** higher than the north". The north polar cap "lies within a 5-kilometer-deep hemispheric depression" | NASA GSFC MOLA release. Zuber et al. 1998, *Science* 282:2053 | https://science.gsfc.nasa.gov/attic/mola/topography.html ; https://doi.org/10.1126/science.282.5396.2053 | CORRECTED to ≈5 km mean (5–6 km is high). Origin: possibly the Borealis giant-impact basin (Andrews-Hanna et al. 2008, https://doi.org/10.1038/nature07011) |
| Olympus Mons summit | **≈21.1–21.3 km above the MOLA datum**: "highest point of the volcano (21,290 m)" (USGS SIM 3470, 2021). 21,229 m (Smith et al. 2001, via secondary). 21.1 km summit in Plescia 2004's grid | Mouginis-Mark 2021, USGS SIM 3470. Plescia 2004 *JGR* (USGS abstract) | https://pubs.usgs.gov/sim/3470/sim3470_pamphlet.pdf ; https://doi.org/10.3133/sim3470 ; https://www.usgs.gov/publications/morphometric-properties-martian-volcanoes | CORRECTED. 21.9 km is Plescia's **relief**, not the summit elevation |
| Olympus Mons height above the plains | **≈22 km** above the NW edge of the Tharsis rise (USGS). JPL press kit: "about **26 km** high and 600 km across" | USGS SIM 3470. JPL InSight press kit | (above) ; https://www.jpl.nasa.gov/news/press_kits/insight/landing/facts/mars-at-a-glance/ | NUANCE. It depends on the base reference (~22–26 km). NASA's *Mars: Facts* page says ">25 miles (40 km) … base to summit", which conflicts with MOLA. Do not use it |
| Olympus Mons width | **~600 km** diameter (USGS). The Plescia 2004 edifice is 840 × 640 km | USGS SIM 3470 | (above) | VERIFIED |
| Basal escarpment | "An escarpment of **up to 10 km** height". The eastern scarp is "nearly **7 km** high" | Weller, McGovern et al. 2014, *JGR Planets* 119 | https://doi.org/10.1002/2013JE004524 | CORRECTED from 6–8 km |
| Tharsis Montes | Ascraeus **≈18 km** above datum (NASA PIA07149: "about 18 km … above the martian datum"). 18.5 km in Parsons & Head 2005. Arsia **≈17.7 km** (NASA public pages say "almost 20 km high"). Pavonis **≈14 km** (ESA: "rises roughly 12 km above the surrounding plains") | NASA/JPL PIA07149. Parsons & Head 2005, LPSC XXXVI #1139. JPL PIA25649. ESA Pavonis Mons | https://www.jpl.nasa.gov/images/pia07149-ascraeus-mons/ ; https://www.lpi.usra.edu/meetings/lpsc2005/pdf/1139.pdf ; https://www.jpl.nasa.gov/images/pia25649-arsia-mons/ ; https://sci.esa.int/web/mars-express/-/39309-pavonis-mons | PARTLY VERIFIED. The proposed 17.8 / 14 / 18.2 km match within ~0.3 km. Plescia 2004 Table 1 (18.1 / 14.0 / 17.7 km) is paywalled and was not read directly |
| Valles Marineris | **~3,870 km long, ~600 km across at its widest, ~9.3 km deep at its deepest** (NASA). ESA: "over 4000 km long and 200 km wide … depth of **10 km**". Another ESA article says "up to 7 km" | NASA *Mars: Facts*. ESA *The Solar System's grandest canyon*. ESA *Mars Express peers into Mars' Grand Canyon* | https://science.nasa.gov/mars/facts/ ; https://www.esa.int/Science_Exploration/Space_Science/The_Solar_System_s_grandest_canyon ; https://www.esa.int/Science_Exploration/Space_Science/Mars_Express/Mars_Express_peers_into_Mars_Grand_Canyon | CORRECTED. Single troughs are ~200 km wide; the central chasmata reach ~600 km. Maximum depth is 8–10 km, not 7 |
| Hellas basin | Diameter **2,300 km**, "depth of over 7 km" (ESA). MOLA release: "nearly 9 km deep and 2,100 km across", with a ~2 km-high rim ring | ESA *Mars Deep down*. NASA GSFC MOLA release | https://www.esa.int/Science_Exploration/Space_Science/Mars_Express/Mars_Deep_down ; https://science.gsfc.nasa.gov/attic/mola/topography.html | VERIFIED |
| Lowest point | **≈ −8.2 km** in Hellas (Smith et al. 2001, via secondary). The 1/4° MOLA MEGDR median grid minimum is −8,068 m | MOLA MEGDR PDS label. Smith et al. 2001, *JGR* 106:23689 | https://pds-geosciences.wustl.edu/mgs/mgs-m-mola-5-megdr-l3-v1/mgsl_300x/meg004/megt90n000cb.lbl ; https://doi.org/10.1029/2000JE001364 | VERIFIED (resolution-dependent) |
| Argyre basin | **~1,800 km** diameter, **~5 km** deep | DLR, *Topographic map of Argyre Planitia* (2014) | https://www.dlr.de/en/images/2014/3/topographic-map-of-argyre-planitia_16348 | VERIFIED |
| Gale crater and Mount Sharp | Gale **154 km** across. Aeolis Mons "rises 18,000 ft (**5,500 m**) from the crater floor" (5.5 km) | NASA/JPL PIA24085. PIA16077 | https://www.jpl.nasa.gov/images/pia24085-gale-crater/ ; https://www.jpl.nasa.gov/images/pia16077-the-heights-of-mount-sharp/ | VERIFIED |
| Jezero crater | "A 28-mile-wide (**45-kilometer-wide**) impact basin" with an "ancient river delta" | JPL Mars 2020 landing press kit | https://www.jpl.nasa.gov/news/press_kits/mars_2020/landing/mission/ | VERIFIED |
| North polar cap | "Approximately **1,000 km** across" (NASA; ESA "spans approximately 1000 km"). Maximum elevation **3 km** above the surroundings. Volume 1.2–1.7 × 10⁶ km³ (about half of Greenland's ice) | NASA *Northern Ice Cap of Mars*. ESA 2013. Zuber et al. 1998 | https://science.nasa.gov/resource/northern-ice-cap-of-mars/ ; https://www.esa.int/ESA_Multimedia/Images/2013/05/Mars_north_polar_ice_cap ; https://doi.org/10.1126/science.282.5396.2053 | VERIFIED (≈3 km thick) |
| South residual cap | "A carbon dioxide ice layer **about 8 meters thick** is being etched away to reveal water ice underneath". In addition, a buried CO2 deposit of **9,500–12,500 km³** lies within the south polar layered deposits (about 30× the residual cap) | Byrne & Ingersoll 2003, *Science* 299:1051. Phillips et al. 2011, *Science* 332:838 | https://doi.org/10.1126/science.1080148 ; https://doi.org/10.1126/science.1203091 | VERIFIED. The buried deposit would raise atmospheric mass by up to 80 % if released |
| Seasonal CO2 caps | Reach "as far as **~55 degrees latitude** by late winter" | NASA *Seasonal Processes: OMEGA Sublimation* | https://science.nasa.gov/resource/seasonal-processes-omega-sublimation | VERIFIED (50–60° is fine; ~55°N and ~50°S are typical maxima). Edge positions repeat within 1–2° from year to year (Piqueux et al. 2015, https://doi.org/10.1016/j.icarus.2014.10.045) |

## 14. Minerals and geologic activity

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| Hematite "blueberries" | Diagenetic **hematite-rich concretions** and vugs in sulfate-rich sediments at Eagle crater, Meridiani | Squyres et al. 2004, *Science* 306:1709 | https://doi.org/10.1126/science.1104559 | VERIFIED |
| Jarosite | Mössbauer spectra show "jarosite- and hematite-rich outcrop", "mineralogical evidence for aqueous processes … under acid-sulfate conditions" | Klingelhöfer et al. 2004, *Science* 306:1740 | https://doi.org/10.1126/science.1104653 | VERIFIED |
| Calcium-sulfate veins, Gale | Veins "consist of calcium sulfate … many of which appear to be hydrated at a level expected for **gypsum and bassanite**. **Anhydrite** is locally present" | Nachon et al. 2014, *JGR Planets* 119:1991 (abstract via Crossref) | https://doi.org/10.1002/2013JE004588 | CORRECTED wording: "Ca-sulfate (gypsum/bassanite)", not "gypsum" alone |
| Perchlorate, Phoenix | **0.4–0.6 % perchlorate by mass**. pH 7.7 ± 0.5. Salts dominated by Mg²⁺ and Na⁺ | Hecht et al. 2009, *Science* 325:64 | https://doi.org/10.1126/science.1172466 | VERIFIED |
| Olivine, Nili Fossae | A 30,000 km² olivine-rich area. Surface exposures about 30 % olivine, Fo30–Fo70 | Hoefen et al. 2003, *Science* 302:627 | https://doi.org/10.1126/science.1089647 | VERIFIED |
| Olivine cumulate, Jezero (Séítah) | "Coarse-grained olivine … an **olivine cumulate**", moderately altered by water | Liu et al. 2022, *Science* 377:1513 | https://doi.org/10.1126/science.abo2756 | VERIFIED |
| Carbonates and clays | Mg-carbonate with phyllosilicate- and olivine-rich units at Nili Fossae (orbital). Aqueously altered igneous crater-floor rocks at Jezero (rover) | Ehlmann et al. 2008, *Science* 322:1828. Farley et al. 2022, *Science* 377:eabo2196 | https://doi.org/10.1126/science.1164759 ; https://doi.org/10.1126/science.abo2196 | VERIFIED |
| Elemental sulfur | Curiosity cracked a rock on **30 May 2024** in the **Gediz Vallis channel**, revealing yellow crystals of **pure (elemental) sulfur**, "the first time this kind of sulfur has been found" on Mars. Announced **18 July 2024** | NASA/JPL news release | https://www.jpl.nasa.gov/news/nasas-curiosity-rover-discovers-a-surprise-in-a-martian-rock/ | VERIFIED |
| Heat Shield Rock | A basketball-size **iron-nickel** meteorite, "the first meteorite of any type ever identified on another planet". JPL release dated **19 Jan 2005** | NASA/JPL news release | https://www.jpl.nasa.gov/news/opportunity-rover-finds-an-iron-meteorite-on-mars/ | VERIFIED |
| Volcanism, precise statement | **No eruption has ever been observed.** The youngest known volcanic deposit is a pyroclastic unit at Cerberus Fossae with a model age of **53 ± 7 to 210 ± 12 ka**. Caldera resurfacing "as young as **two million years**", "volcanoes are potentially still active". Elysium lava flows date to ~3 Ma. Geophysical evidence points to an active mantle plume under Elysium, and InSight marsquakes cluster at Cerberus Fossae | Horvath et al. 2021, *Icarus* 365:114499 (U. Arizona abstract). Neukum et al. 2004, *Nature* 432:971. PSI release 2021. Broquet & Andrews-Hanna 2022, *Nat. Astron.* Stähler et al. 2022, *Nat. Astron.* 6:1376 | https://doi.org/10.1016/j.icarus.2021.114499 ; https://experts.arizona.edu/en/publications/evidence-for-geologically-recent-explosive-volcanism-in-elysium-p-2/ ; https://doi.org/10.1038/nature03231 ; https://www.psi.edu/blog/volcanoes-on-mars-could-be-active-raise-possibility-of-recent-habitable-conditions/ ; https://doi.org/10.1038/s41550-022-01836-3 ; https://doi.org/10.1038/s41550-022-01803-y | NUANCE. Better wording: "no active eruptions; volcanism geologically recent (≤ ~0.1–3 Myr) and possibly dormant, not extinct" |

## 15. Weather

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| Dust devils (Spirit) | One season ran from Ls 173.2° to 339.5° (southern spring and summer), with **533** dust devils observed. Diameters **2–276 m** (mostly 10–20 m). Active ~09:30–16:30 local time, peak at ~13:00 and Ls ~250°. Horizontal speeds <1–21 m/s, vertical winds 0.2–8.8 m/s. ~50 per km² per sol | Greeley et al. 2006, *JGR Planets* 111(E12) (abstract via Crossref) | https://doi.org/10.1029/2006JE002743 | VERIFIED. Good spawn parameters |
| Global dust storms | **2001 (MY25)**, **2007 (MY28)**, **2018 (MY34)** | Wolkenberg et al. 2020 | https://ntrs.nasa.gov/api/citations/20210012819/downloads/21-56.pdf | VERIFIED. The paper labels the MY28 storm "2008", but it began in mid-2007 at Ls ~265° |
| 2018 storm and Opportunity | "A **tau of about 10.8**" on **10 June 2018** (sol 5,111), the day of the last data. Mission declared over **13 Feb 2019** after more than 1,000 recovery commands. Curiosity at Gale measured τ ≈ **8.5** (880 nm) | NASA/JPL PIA22930. JPL end-of-mission release. Viúdez-Moreiras et al. 2019 | https://www.jpl.nasa.gov/images/pia22930-last-images-opportunity-took/ ; https://www.jpl.nasa.gov/news/nasas-opportunity-rover-mission-on-mars-comes-to-end/ ; https://pmc.ncbi.nlm.nih.gov/articles/PMC6750032/ | VERIFIED |
| CO2 snow (polar night) | MCS shows extensive tropospheric **CO2 clouds**. Surface deposits are "likely emplaced by snowfall". A ~500 km-wide cloud persists over the south polar residual cap all winter | Hayne et al. 2012, *JGR Planets* 117(E8) | https://doi.org/10.1029/2011JE004040 | VERIFIED |
| Water-ice clouds and snow | The Phoenix lidar saw cirrus-like water-ice clouds with **fall streaks** of ice crystals precipitating toward the ground at night | Whiteway et al. 2009, *Science* 325:68 | https://doi.org/10.1126/science.1172344 | NUANCE. There is no rain, but water-ice snow (virga) and CO2 snow do occur |
| Winds | 2–7 m/s (summer), 5–10 m/s (fall), 17–30 m/s (dust storm) at the Viking sites | NSSDCA | https://nssdc.gsfc.nasa.gov/planetary/factsheet/marsfact.html | |

## 16. Radiation and magnetic field

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| Surface dose (Curiosity RAD) | **0.64 ± 0.12 mSv/day** dose equivalent. **0.210 ± 0.040 mGy/day** absorbed. The press release gives 0.67 mSv/day (Aug 2012 to Jun 2013) | Hassler et al. 2014, *Science* 343:1244797 (values as quoted by Atri et al. 2023). SwRI release (9 Dec 2013) | https://doi.org/10.1126/science.1244797 ; https://arxiv.org/abs/2208.00892 ; https://www.swri.org/newsroom/press-releases/swri-scientists-publish-first-radiation-measurements-the-surface-of-mars | VERIFIED |
| Cruise dose | "Average GCR dose equivalent rate of **1.8 mSv per day** in cruise". Round trip of **0.66 ± 0.12 Sv** (180 days each way) | Zeitlin et al. 2013, *Science* 340:1080 (abstract). SwRI and JPL releases (30–31 May 2013) | https://doi.org/10.1126/science.1235989 ; https://www.swri.org/newsroom/press-releases/swri-led-team-calculates-the-radiation-exposure-associated-trip-mars ; https://www.jpl.nasa.gov/news/data-from-nasa-rovers-voyage-to-mars-aids-planning/ | VERIFIED (1.8). The paper's 1.84 ± 0.33 is commonly quoted but I did not read it directly |
| Magnetic field | **No global field.** Crustal magnetization sits mainly in the ancient southern highlands, with moments up to 1.3 × 10¹⁷ A·m² (Terra Sirenum). There is none in Hellas or Argyre, so the dynamo stopped ~4 Ga | Acuña et al. 1999, *Science* 284:790. NASA *Mars: Facts* | https://doi.org/10.1126/science.284.5415.790 ; https://science.nasa.gov/mars/facts/ | VERIFIED. Alternating-polarity lineations: Connerney et al. 1999, https://doi.org/10.1126/science.284.5415.794 |

## 17. Sound

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| Speed of sound | Two values "about 10 m/s apart below and above **240 Hz**": **237.7 ± 3 m/s** below (Ingenuity, 84 Hz) and **246–257 m/s** above (laser sparks) | Maurice et al. 2022, *Nature* 605:653 (PMC) | https://doi.org/10.1038/s41586-022-04679-0 ; https://pmc.ncbi.nlm.nih.gov/articles/PMC9132769/ | VERIFIED |
| Loudness | Sounds are "**~20 dB weaker** than on Earth when produced by the same source". An 8 kHz tone is attenuated −9 dB at 2 m and −40 dB at 8 m | Maurice et al. 2022 | same | VERIFIED. Low-pass filter distant sounds strongly |

## 18. In-situ resource utilisation (ISRU)

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| MOXIE totals | **122 g O2** in total, "about what a small dog breathes in 10 hours". Best rate **12 g/h**, "twice as much as NASA's original goals". Purity "**98% purity or better**". **16 runs**; the last, on 7 Aug 2023, made 9.8 g | NASA/JPL release (6 Sep 2023) | https://www.jpl.nasa.gov/news/nasas-oxygen-generating-experiment-moxie-completes-mars-mission/ | VERIFIED. Source the totals to NASA 2023, not to Hoffman 2022, which covers only the 7 runs of 2021 |
| MOXIE method | **Solid-oxide electrolysis of atmospheric CO2** (2CO2 → 2CO + O2) | Hoffman et al. 2022, *Sci. Adv.* 8:eabp8636 | https://doi.org/10.1126/sciadv.abp8636 | VERIFIED |
| Sabatier and electrolysis | **CO2 + 4H2 → CH4 + 2H2O**. Water goes to electrolysis (**2H2O → 2H2 + O2**). This is how the ISS Carbon Dioxide Reduction Assembly and Oxygen Generation Assembly work | NASA NTRS 20100036570 (ISS CRA and Sabatier) | https://ntrs.nasa.gov/citations/20100036570 | VERIFIED (standard chemistry) |

## 19. Human factors

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| O2 consumption | **0.84 kg/day** (HIDH reference crewmember, short mission, BVAD Table 3-23). The current nominal value is **0.895 kg/crewmember-day** (82 kg crew with daily exercise, Table 3-31) | NASA *Life Support Baseline Values and Assumptions Document*, NASA/TP-2015-218570/REV2 (Feb 2022) | https://ntrs.nasa.gov/citations/20210024855 | VERIFIED, with the nuance. Use 0.84–0.90 kg/day |
| EMU suit pressure | Nominal EVA **4.3 psid ≈ 29.6 kPa**, 100 % O2. Secondary 3.7 psid | Ogilvie & Campbell 2023, ICES-2023-027 (NASA JSC), Table 1 | https://ntrs.nasa.gov/api/citations/20230007781/downloads/Regulators_ICES2023_Final.pdf | VERIFIED. Apollo 3.85 psid, Orlan-M 5.8 psid |
| xEMU suit pressure | **4.3 psid nominal EVA, variable 0–8.2 psid** (8.2 psid ≈ 56.5 kPa for decompression-sickness treatment and reduced prebreathe). "Operate at a nominal pressure of 8.2 or 4.3 psid depending on use case" | Ogilvie & Campbell 2023. Davis et al. 2022, ICES (xEMU waist/brief/hip) | (above) ; https://ntrs.nasa.gov/api/citations/20220009186/downloads/xEMU%20WBH%20ICES%20Final.pdf | VERIFIED (4.3–8.2 psi) |

## 20. Solar power and dust

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| Dust settling rate (Pathfinder) | "Steady dust accumulation at a rate of about **0.28% per day**". Lander arrays lost ~0.29 % per day | Landis & Jenkins 2000, *JGR* 105:1855 | https://doi.org/10.1029/1999JE001029 | VERIFIED |
| MER Dust Factor and cleaning | The **Dust Factor** is the fraction of sunlight that gets through the dust on the array (1.0 = clean). Thanks to lower-than-expected accumulation and "numerous dust cleaning events", Spirit lasted **>2,000 sols** and Opportunity **>5,000 sols**. Meridiani showed a seasonal accumulation and removal pattern | Chin, Wood & Herman 2021 (JPL), *Power Operations of the Mars Exploration Rovers* | https://ntrs.nasa.gov/citations/20230007029 | VERIFIED. Lorenz et al. 2021, *PSS* 207:105337 (https://doi.org/10.1016/j.pss.2021.105337), reported in its abstract as ~0.2 % per sol typical (0.05–2 %). I read that only through a search summary |
| InSight end | Last contact **15 Dec 2022**. Mission retired **21 Dec 2022** after dust on the solar panels "gradually reduced its energy". The seismometer recorded **1,319** marsquakes | NASA/JPL release (21 Dec 2022) | https://www.jpl.nasa.gov/news/nasa-retires-insight-mars-lander-mission-after-years-of-science/ | VERIFIED |

---

# Part B: Starship and SpaceX

The specifications below come from SpaceX's own website. It is a single-page app; the values were read from its
content API and JS bundle on 2026-10-04. They describe the **V3** vehicle, the configuration flown since Flight 12
(22 May 2026).

## 21. Vehicle

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| Full stack | **124 m (407 ft)**, 9 m diameter, **100+ t** reusable payload | SpaceX *Starship* vehicle page (V3) | https://www.spacex.com/vehicles/starship/ | VERIFIED for V3. V1 ≈121 m and V2 ≈123 m (Wikipedia pointer: 121.3 / 123.3 m), not re-verifiable from SpaceX |
| Ship height | **52 m (171 ft)** (V3) | SpaceX *Starship* page | same | VERIFIED. V1 50.3 m and V2 52.1 m (Wikipedia pointer). SN8 was 50 m (Spaceflight Now, 2020) |
| Diameter | **9 m** | SpaceX | same | VERIFIED |
| Engines (ship) | **6**: "three Raptor engines, and three Raptor Vacuum (RVac)" | SpaceX | same | VERIFIED |
| Flaps | **4** ("two forward and two aft flaps", SN15; "Using its four flaps", Flight 10) | SpaceX SN15 and Flight 10 pages | https://www.spacex.com/launches/starship-sn15 ; https://www.spacex.com/launches/starship-flight-10 | VERIFIED. V3 uses a single actuator with three motors per aft flap (SpaceX V3 update) |
| Hull steel | Early prototypes used **301** stainless. SpaceX then moved to **304L** for better cryogenic toughness, and since then to in-house alloys (Musk: "30X") | Space.com (M. Wall, 12 Mar 2020). Musk on X | https://www.space.com/spacex-starship-new-stainless-steel-alloy.html | PARTLY VERIFIED. "30X" is from Musk's posts and reports, not from a SpaceX document |
| Heat shield | Black **hexagonal** ceramic tiles, about 18,000 per ship, rated ≈1,400 °C | Secondary (CNBC 2021 via Wikipedia). SpaceX flight pages discuss TPS tests | https://en.wikipedia.org/wiki/SpaceX_Starship_(spacecraft) | PARTLY VERIFIED (secondary sources only). CNN, Flight 5: reentry "as high as 2,600 °F" per SpaceX engineers |
| Ship propellant | **1,600 t (V3)**. V2 1,500 t (Kingdon 2025 assumption; Wikipedia "+25 %"). V1 1,200 t (Heldmann et al. 2022) | SpaceX *Starship* page | https://www.spacex.com/vehicles/starship/ | VERIFIED for V3. The range 1,200–1,600 t covers V1–V3 |
| Ship thrust | **1,614 tf (3.5 Mlbf)** (V3, as listed) | SpaceX | same | Note: 3 × 250 + 3 × 275 = 1,575 tf. SpaceX's listed total is slightly higher |
| Super Heavy height | **72 m (236 ft)** (V3, with integrated hot stage) | SpaceX | same | VERIFIED. The V1 booster was ~71 m including the 1.8 m hot-stage ring (Wikipedia pointer) |
| Booster propellant | **3,650 t (8 Mlb)** (V3) | SpaceX | same | VERIFIED for V3. V1 figures disagree: SpaceX's earlier site said 3,400 t, Wikipedia says 3,250 t. UNVERIFIED |
| Booster engines | **33 Raptors: 13 gimballed in the centre, 20 around the perimeter** | SpaceX | same | VERIFIED |
| Grid fins | **4 on V1/V2 boosters. 3 on V3**, "each fin now 50% larger", lowered and re-clocked, with a catch point | SpaceX *Introducing Starship V3* (12 May 2026) | https://www.spacex.com/updates/ | CORRECTED (version-specific) |
| Liftoff thrust | **8,240 tf (18.1 Mlbf)** (V3, 33 × 250 tf). V1/V2: **≈7,590 tf** = 33 × 230 tf (Raptor 2) | SpaceX page. Arithmetic from the Raptor 2 thrust in the V3 update | https://www.spacex.com/vehicles/starship/ | VERIFIED |
| Raptor 2 | **230 tf (507 klbf) sea level**, **258 tf (568 klbf) vacuum**. Mass 1,630 kg. Main chamber **300 bar** ("Raptor 2 now operates routinely at 300 bar main chamber pressure", Musk, Jan 2022) | SpaceX V3 update. Musk on X | https://www.spacex.com/updates/ ; https://x.com/elonmusk/status/1478125263233990657 | VERIFIED |
| Raptor 3 | **250 tf (551 klbf) sea level**, **275 tf (606 klbf) vacuum**. Sea-level mass **1,525 kg**. Engine shrouds deleted. Dimensions on SpaceX's site: sea level 1.3 m × 2.9 m, RVac 2.3 m × 4.4 m | SpaceX V3 update (12 May 2026). SpaceX *Starship* page | https://www.spacex.com/updates/ ; https://www.spacex.com/vehicles/starship/ | CORRECTED. SpaceX's August 2024 graphic listed 280 tf, a target (known only from secondary reports). The current official figure is 250 tf. Chamber pressure (330–350 bar) is UNVERIFIED |
| Specific impulse | SpaceX's Aug 2024 graphic, as reported by secondary sources, lists Isp **350 s (Raptor 1), 347 s (Raptor 2), 350 s (Raptor 3)**, without saying whether these are sea-level or vacuum values. Wikipedia gives ≈327 s at sea level for Raptor 1. RVac ≈**380 s** in vacuum (Musk 2019, via Wikipedia) | Secondary reports of the SpaceX graphic. Wikipedia pointer | https://en.wikipedia.org/wiki/SpaceX_Raptor | UNVERIFIED from a primary SpaceX document. Reasonable game values: sea-level engine ≈330 s at sea level and ≈350 s in vacuum; RVac ≈380 s |
| Chamber construction | "Raptor uses **milled copper channels with an inconel jacket** all the way down" (Musk, Sep 2019): a regeneratively cooled copper-alloy liner. **SX500** superalloy (in-house) for "12000 psi, hot oxygen-rich gas" | Musk on X | https://x.com/elonmusk/status/1177387141116002304 ; https://x.com/elonmusk/status/1076684059827302400 | VERIFIED (copper alloy). The specific alloy (NASA's GRCop-42) has **never been confirmed** by SpaceX: UNVERIFIED |

## 22. Flight-test timeline and telemetry

Official SpaceX post-launch timelines (T+ h:mm:ss). Flights 10–11 are V2 ships on V1 boosters. Flights 12–13 are V3.
Flight 4 and 5 times come from Wikipedia's tables, which cite SpaceX and the webcast; SpaceX's pages now hold only the
narrative for those flights.

| Event | Flight 4 (6 Jun 2024) | Flight 5 (13 Oct 2024) | Flight 10 (26 Aug 2025) | Flight 11 (13 Oct 2025) | Flight 12 (22 May 2026, V3) | Flight 13 (24 Jul 2026, V3) |
|---|---|---|---|---|---|---|
| Max Q | 1:02 | 1:02 | 1:02 | 1:02 | 0:45 | 0:58 |
| Booster MECO | 2:46 | 2:35 | 2:36 | 2:37 | 2:22 | 2:18 |
| Hot staging | after MECO (≈2:46–2:50, not listed) | 2:40 | 2:38 | 2:39 | 2:24 | 2:21 |
| Boostback start / end | — | 2:45 / 3:41 | 2:48 / 3:38 | 2:49 / 3:38 | 2:30 / 3:30 | 2:25 / 3:03 |
| Booster landing burn / end | splashdown 7:24 | 6:30 / **catch 6:54** | 6:20 / 6:40 | 6:20 / 6:36 | 6:34 / 6:59 | 6:27 / 6:53 |
| Ship engine cutoff (SECO) | 8:37 | 8:27 | 8:57 | 8:58 | 8:11 | 8:05 |
| Entry | — | 48:03 | 47:29 | 47:43 | 47:47 | 47:30 |
| Landing flip / burn | 1:05:36 | — | flip 1:06:14, burn 1:06:20 | burn 1:05:58, flip 1:06:00 | burn 1:05:06, flip 1:05:08 | burn 1:05:01, flip 1:05:03 |
| Splashdown | 1:05:56 | 1:05:40 | 1:06:30 | 1:06:25 | 1:05:26 | 1:05:21 |

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| Max Q time | **T+1:02** (V1/V2). **T+0:45–0:58** (V3) | SpaceX flight pages 10–13. Wikipedia Flight 5 | https://www.spacex.com/launches/starship-flight-10 ; https://www.spacex.com/launches/starship-flight-12 ; https://en.wikipedia.org/wiki/Starship_flight_test_5 | VERIFIED (0:52–1:02 is fine) |
| MECO / hot staging | V1/V2: **MECO T+2:35–2:46, staging T+2:38–2:40** (Flights 5, 10, 11). V3: **MECO 2:18–2:22, staging 2:21–2:24** | SpaceX timelines. Wikipedia Flights 4–5 | (above) ; https://en.wikipedia.org/wiki/Starship_flight_test_4 | NUANCE. ~T+2:40 is right for V1/V2 only |
| Hot-staging altitude and speed | Fan-wiki webcast transcriptions: Flight 4 ≈**5,475 km/h at 72 km**; Flight 8 ≈4,454 km/h at 61 km | starship-spacex.fandom.com (fan wiki, via search summaries; the pages were not readable from here) | https://starship-spacex.fandom.com/wiki/Starship_Flight_Test_4 | UNVERIFIED. Physically plausible. Use ≈65–72 km and ≈5,000–5,600 km/h with a "webcast approx." caveat |
| Booster catch | First catch **13 Oct 2024 (Flight 5)**: "on our first try, Mechazilla caught the booster". Second on Flight 7 (16 Jan 2025), third on Flight 8 (6 Mar 2025) | SpaceX Flight 5, 7 and 8 pages | https://www.spacex.com/launches/starship-flight-5 | VERIFIED |
| SECO time | **T+8:05–8:58** (Flight 5: 8:27) | SpaceX timelines. Wikipedia | (above) | VERIFIED (~T+8:30) |
| Ship speed after SECO | "Coasting at about **26,200 km/h**" (Flight 5). Flight 2: "an altitude of ~150 km and a velocity of ~24,000 km/h" (burn incomplete); Everyday Astronaut read 24,124 km/h at 148 km from the webcast | CNN live blog, Flight 5. SpaceX Flight 2 page. Everyday Astronaut | https://www.cnn.com/science/live-news/spacex-starship-launch-5-10-13-24/index.html ; https://www.spacex.com/launches/starship-flight-2 ; https://everydayastronaut.com/starship-superheavy-flight-test-2/ | VERIFIED (~26,000 km/h). Suborbital trajectory: apogee ≈212–213 km, perigee ≈ −15 km (Flights 4–5, Wikipedia) |
| Splashdown time | Flight 5 ended "1 hour, 5 minutes and 40 seconds after launch" | SpaceX Flight 5 page | https://www.spacex.com/launches/starship-flight-5 | VERIFIED |
| Orbital flights | The first 13 flights were deliberately suborbital. **From Flight 14**, Starship flies orbital missions with a single-Raptor circularisation burn, and later a single-Raptor deorbit burn | SpaceX *Starship to Orbit* (15 Sep 2026) | https://www.spacex.com/updates/ | Context for the mod's ascent sequence |

## 23. Mars mission profile

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| Orbital refilling | Tankers deliver CH4/LOX in LEO. V3 adds **four docking drogues** and ship-to-ship propellant feed connections. Lunar HLS: NASA officials have cited a "high teens" number of launches per landing (~15–17). One Mars study models **15 refuels** per crew ship for a 90-day transit | SpaceX V3 update. Kingdon 2025, *Sci. Rep.* 15:17764. The HLS figure attributed to NASA's L. Watson-Morgan comes from search summaries of AmericaSpace and Space.com reports, not read directly | https://www.spacex.com/updates/ ; https://doi.org/10.1038/s41598-025-00565-7 | UNVERIFIED as an official SpaceX Mars number. "4–10+" is defensible; minimum-energy profiles need fewer tankers than fast ones |
| TMI Δv from LEO | **≈3.6 km/s** for a minimum-energy (~180 d) transfer: 3.57 km/s from 150 km LEO (Kingdon 2025). COMPUTED 3.57–3.62 km/s. A 90-day transit needs **4.6 km/s** | Kingdon 2025 (PMC) | https://pmc.ncbi.nlm.nih.gov/articles/PMC12099006/ | VERIFIED |
| Mars entry speed | "Starship will enter Mars' atmosphere at **7.5 kilometers per second** and decelerate aerodynamically" | SpaceX *Mars* page | https://www.spacex.com/humanspaceflight/mars/ | VERIFIED. A Hohmann arrival would be only ≈5.6 km/s (COMPUTED); 7.5 km/s implies a faster transit (v∞ ≈ 5.6 km/s) |
| Earth return entry | **≈11.5 km/s** for a minimum-energy return, **12–13 km/s** for fast returns (COMPUTED at 125 km). Kingdon 2025 example: perigee speed "about 11.2 km/s" | COMPUTED. Kingdon 2025 | (appendix) | NUANCE. "~12 km/s" is fine for a fast return |
| Mars ascent | Surface to a 100 km low Mars orbit: **4.2 km/s** (includes 20 % gravity loss). 500 km circular: 4.4 km/s. 1-sol elliptical orbit: 5.7 km/s | Palaszewski 2021 (NASA GRC), *Mars Landing Vehicles: Descent and Ascent Propulsion Design Issues* | https://ntrs.nasa.gov/citations/20210026187 | VERIFIED (~4.1–4.2 km/s) |
| Direct return from the surface | **≈5.4–5.9 km/s ideal**, **≈6 km/s with losses** | COMPUTED: √(v∞² + v_esc²) − v_rot, with v_esc = 5.02 km/s and v_rot = 0.24 km/s | (appendix) | VERIFIED (~6 km/s) |
| ISRU propellant | Refilling a 1,200 t Starship at O/F ≈ 3.5 needs **≈933 t O2 + 267 t CH4**, made from **≈600 t of water** (a cube about 9 m on a side) plus atmospheric CO2. SpaceX: "Starship was designed from the beginning to run off of liquid methane and oxygen, natural resources that can be mined and refined on Mars" | Heldmann et al. 2022, *New Space* 10(3):259 (NASA Ames). SpaceX Mars page | https://doi.org/10.1089/space.2020.0058 ; https://pmc.ncbi.nlm.nih.gov/articles/PMC9527650/ ; https://www.spacex.com/humanspaceflight/mars/ | VERIFIED. The original IAC talks (Musk 2017, https://doi.org/10.1089/space.2017.29009.emu ; Musk 2018, https://doi.org/10.1089/space.2018.29013.emu) are paywalled and were not re-read |
| SpaceX's current Mars plans | Cargo flights to Mars "no earlier than **2028**, at a rate of $100 million per metric ton". A planned crewed Mars **fly-by** mission lasting about two years, announced 21 May 2026 | SpaceX Mars and Starship pages. SpaceX update (21 May 2026) | https://www.spacex.com/humanspaceflight/mars/ ; https://www.spacex.com/updates/ | Context |

## 24. Ship landing sequence

| Quantity | Verified value | Source (short) | URL | Notes/corrections |
|---|---|---|---|---|
| Belly-flop terminal velocity | **≈75–90 m/s (≈270–325 km/h)** near the ground. Tim Dodd: "Starship's belly flop terminal velocity is already only like 75 m/s". Everyday Astronaut's SN8 simulation: ~150 m/s peak, slowing to ~90 m/s | Everyday Astronaut article and X post | https://everydayastronaut.com/starships-belly-flop-maneuver/ ; https://x.com/erdayastronaut/status/1369485777357377544 | UNVERIFIED officially. SpaceX has published no number. 270–300 km/h is a reasonable game value |
| Flip altitude | **≈500–550 m**. Everyday Astronaut's analysis starts the flip at ≈550 m (starting higher costs ~370 m/s more Δv). SN15 reportedly relit two Raptors at ~500 m | Everyday Astronaut | https://everydayastronaut.com/starships-belly-flop-maneuver/ | Secondary analysis, not a SpaceX figure |
| SN8 and SN15 hops | **SN8, 9 Dec 2020**: ascended (target 12.5 km), "performed its landing flip maneuver with precise flap control". Low header-tank pressure caused a hard landing. **SN15, 5 May 2021**: apogee ~10 km, four flaps, "nominal landing on the pad" (the first successful landing) | SpaceX SN8 and SN15 pages | https://www.spacex.com/launches/starship-sn8 ; https://www.spacex.com/launches/starship-sn15 | VERIFIED |
| Ship splashdowns with flip and burn | **Flight 4**: "first flip maneuver and landing burn since our suborbital campaign", soft splashdown in the Indian Ocean at ~1:06. **Flight 5**: flip, landing burn, splashdown at 1:05:40. **Flight 6**: in-space Raptor relight, flip, landing burn, soft splashdown | SpaceX Flight 4, 5 and 6 pages | https://www.spacex.com/launches/starship-flight-4 ; https://www.spacex.com/launches/starship-flight-5 ; https://www.spacex.com/launches/starship-flight-6 | VERIFIED. On Flights 11–13 the landing burn starts about 2 s **before** the flip, then steps down 3 → 2 engines (Flight 11) or 3 → 2 → 1 (Flights 12–13). See the §22 table. On Flight 10 the flip came 6 s before the burn |

---

## Appendix: computed values and formulas

Constants used: NSSDCA (2025) GM_Mars = 42,828.37 km³/s², R_eq = 3396.2 km, R_mean = 3389.5 km, P_sid = 24.6229 h,
sol = 24.6597 h, a = 227.956 × 10⁶ km, q = 206.650 × 10⁶ km, Q = 249.261 × 10⁶ km. 1 AU = 149,597,870.7 km.
TSI = 1361 W/m². R☉ = 695,700 km. μ☉ = 1.32712440018 × 10¹¹ km³/s². μ⊕ = 398,600.44 km³/s², R⊕ = 6378.137 km.
Atmospheric M ≈ 43.3–43.5 g/mol (43.34 used; the difference is negligible), R = 8.314 J/(mol·K).

| Quantity | Formula | Result |
|---|---|---|
| Sun angular diameter | 2·atan(R☉/d) | 0.3497° (mean), 0.3858° (perihelion), 0.3198° (aphelion) |
| Irradiance | 1361·(1 AU/d)² | 586.1 / 713.2 / 490.2 W/m². Orbit average 1361/(a²√(1−e²)) = 588.7 |
| Phobos synodic period | 1/(1/7.6538 − 1/24.6229) h | 11.106 h, 2.22 transits per sol |
| Phobos time up (equator) | [2·acos(R/a)/360°]·P_syn | 137.5° → 4.24 h |
| Phobos latitude limit | acos(R/a), + i, + apoapsis, planetographic | 68.76° → 69.84° → 70.17° → 70.39° |
| Phobos angular size | D/(a − R): 22.8 km and 18.2 km at 5,980 km | 0.218° × 0.174° (zenith). 0.149° × 0.119° at the horizon (8,739 km) |
| Deimos synodic / time up | 1/(1/24.6229 − 1/30.2986) h; arc 2·acos(R/a) | 131.4 h; 163.35° → 59.6 h = 2.42 sols |
| Deimos angular size | 12.0 km and 10.2 km at 20,063 km | 2.06′ × 1.75′ |
| Earth greatest elongation | asin(r_E/r_M) | 41.0° (mean). 47.4° max, 36.2° min |
| Earth magnitude from Mars | V(1,0) + 5 log(rΔ) − 2.5 log Φ_Lambert(α), V(1,0) = −3.99 | ≈ −2.45 (mean Mars distance). ≈ −2.95 (Mars at perihelion) |
| Light time | d/c | 3.04 min (54.6 Gm) to 22.3 min (401.4 Gm) |
| Hohmann Earth→Mars | π√(a³/μ☉), a = 1.26183 AU | 258.9 d. v∞: 2.945 km/s at Earth departure, 2.649 km/s at Mars arrival |
| TMI Δv | √(v∞² + 2μ⊕/r) − √(μ⊕/r) | 3.62 (150 km), 3.61 (200 km), 3.57 km/s (400 km) |
| Entry speed at 125 km | √(v∞² + 2μ/r) | Mars: 5.60 km/s (Hohmann), 7.77 km/s (v∞ = 6). Earth: 11.46 (Hohmann), 12.59 km/s (v∞ = 6) |
| Mars surface → Earth return (ideal) | √(v∞² + v_esc²) − v_rot | 5.44 km/s (v∞ = 2.65) to 5.88 km/s (v∞ = 3.5), before losses |
| CO2 frost point | T_c = −3167.8/[ln(0.01p) − 23.23] (Fanale et al. 1982) | 72 Pa: 134.5 K. 610 Pa: 147.9 K. 1240 Pa: 152.9 K |
| Density / scale height | ρ = pM/RT; H = RT/(Mg) | 0.0151 kg/m³ (610 Pa, 210 K); H = 10.8 km (210 K) |
| Pressure vs altitude | p = p0·exp(−z/H) with p0 = 610 Pa | Hellas (−7.15 km) 1.16–1.25 kPa; Olympus summit (21.23 km) 73–90 Pa (H = 10–11.1 km) |
| Water vapour pressure at 37 °C | Buck (1996) | 6.28 kPa = 47.1 mmHg (Armstrong limit) |
| Equatorial circumference | 2π·3396.2 km | 21,339 km |
