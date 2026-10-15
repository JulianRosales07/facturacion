# Sprint 1 — Dominio + Reglas de Cálculo (Lógica Pura sin BD)

- **Servicio:** `corporate-billing-service`
- **Paquete:** `com.sistemaspalacios.billing.domain`
- **Historias de Usuario Cubiertas:** HU-10, HU-11, HU-12, HU-13, HU-14, HU-15, HU-16, HU-18.
- **Estado:** Completado y Verificado en Verde (100% pruebas unitarias pasando).

---

## 1. Objetivo del Sprint

Implementar el corazón financiero y tributario del microservicio como **lógica de dominio pura**, testeada exhaustivamente de forma aislada, sin dependencias de infraestructura ni base de datos, garantizando precisión en cálculos monetarios mediante `BigDecimal` e inmutabilidad.

---

## 2. Historias de Usuario Implementadas

| ID | Historia de Usuario | Componente Principal |
|---|---|---|
| **HU-10** | Fecha de cobro a partir de certificación | `BillingCalculator.resolveEffectiveBillingDate()` |
| **HU-11** | Prorrateo de días en altas a mitad de periodo | `ProrationCalculator.calculate()` |
| **HU-12** | Cobro de instalación en cuotas pactadas | `InstallationFee`, `BillingCalculator` |
| **HU-13** | Aplicación de descuentos comerciales | `InvoiceItem.getDiscountValue()`, `InvoiceItem.getTaxableBase()` |
| **HU-14** | Cálculo de IVA (19%) por ítem gravado | `TaxCalculator.calculateIva()`, `InvoiceItem.getIvaValue()` |
| **HU-15** | Retenciones tributarias solo para el neto a pagar | `TaxCalculator.calculateWithholdings()`, `Invoice.getNetPayableAmount()` |
| **HU-16** | Generación de asiento contable balanceado | `AccountingEntryGenerator.generateEntry()` |
| **HU-18** | Formato de descripción de línea para Siigo | `BillingCalculator.formatItemDescription()` |

---

## 3. Arquitectura del Dominio y Modelado

### 3.1. Value Objects Inmutables (`domain.vo`)
1. **`Money`**: Representación inmutable de valores monetarios encapsulando `BigDecimal` con escala a 2 decimales y redondeo `RoundingMode.HALF_UP`. Prohíbe el uso de tipos flotantes primitivos (`double`, `float`). Implementa operaciones seguras: `plus()`, `minus()`, `multipliedBy()`, `percentage()`, `isPositive()`, `isZero()`.
2. **`DateRange`**: Rango cerrado de fechas `[startDate, endDate]` para validar pertenencia de eventos, prorrateos y vigencias impositivas.
3. **`TaxProfile`**: Perfil tributario del cliente que encapsula si aplica retención, municipio de operación y tarifas específicas.
4. **`WithholdingDetail`**: Desglose de cada concepto retenido (código, concepto, base gravable evaluada, tarifa aplicada y valor deducido).
5. **`ProrationResult`**: Resultado del cálculo de prorrateo (días facturables, días base del mes, factor y valor resultante).
6. **`AccountingEntryLine`**: Línea individual del asiento contable de Siigo (secuencia, cuenta contable, naturaleza D/C, tercero, sucursal, valor y descripción).

### 3.2. Entidades de Dominio Puras (`domain.model`)
- **`CorporateClient`**: Cliente corporativo con NIT, razón social, tipo de facturación (F1/F20), municipio ICA y perfil fiscal.
- **`SubscriptionSite`**: Sede corporativa con código NIC, canon mensual base y fecha de certificación técnica.
- **`Invoice`**: Agregado raíz de facturación que consolida todos los ítems de las sedes de un cliente para un periodo, calculando subtotal, IVA, total legal de la factura y neto a pagar tras retenciones. Captura además el *snapshot fiscal inmutable* del cliente a la fecha de corte.
- **`InvoiceItem`**: Línea facturada correspondiente a un canon de sede o cuota de instalación, registrando valor unitario, porcentaje de descuento, base gravable e IVA liquidado.
- **`InstallationFee`**: Control de amortización de la instalación (monto total pactado, cuotas totales y cuotas facturadas).
- **`OrderUpdate`**: Novedad operativa proveniente de órdenes (ALTA, BAJA, CAMBIO_PLAN) con observaciones que pueden sobrescribir fechas.
- **`TaxConceptValidity`**: Tarifa y base mínima de retención por concepto fiscal (ReteFuente, ReteICA, ReteIVA, Estampillas) con rango de vigencia `[fechaInicio, fechaFin]`.

---

## 4. Reglas de Negocio y Servicios de Dominio

### 4.1. Fecha Efectiva de Cobro y Prorrateo (HU-10, HU-11)
- **Regla HU-10:** La fecha de cobro inicial corresponde a la `fecha_certificacion` de la sede técnica. Si existe una observación contractual en la novedad (ej. *"Cobro comercial a partir de 2026-09-15 por acuerdo"*), el parser de `BillingCalculator` extrae y prioriza la fecha de la observación.
- **Regla HU-11 (Prorrateo 30 días base):**
  $$\text{Días Facturables} = 30 - \text{DíaInicio} + 1$$
  $$\text{Valor Prorrateado} = \text{CanonMensual} \times \left( \frac{\text{Días Facturables}}{30} \right)$$
  - Si la certificación ocurre el día 1 del periodo, no aplica prorrateo y se cobra el mes completo.
  - Si una observación estipula *"Cobro mes completo"*, se anula el prorrateo.

### 4.2. Instalación en Cuotas (HU-12)
- Cada cuota se factura como un ítem de tipo `INSTALLATION_FEE`.
- Al incluirse en una factura, el contador de cuotas facturadas se incrementa (`billedInstallments++`).
- Cuando `billedInstallments == totalInstallments`, el estado cambia a `COMPLETED` y no se vuelve a incluir en periodos posteriores.

### 4.3. Descuentos e IVA 19% (HU-13, HU-14)
- **Descuentos (HU-13):** Aplican directamente sobre el valor bruto del ítem:
  $$\text{Valor Descuento} = \text{Valor Bruto} \times \left( \frac{\% \text{ Descuento}}{100} \right)$$
  $$\text{Base Gravable} = \text{Valor Bruto} - \text{Valor Descuento}$$
- **IVA (HU-14):** Se calcula sobre la base gravable resultante:
  $$\text{IVA} = \begin{cases} \text{Base Gravable} \times 0.19 & \text{si el ítem es gravado} \\ 0 & \text{si el ítem es excluido/no gravado} \end{cases}$$
  $$\text{Total Ítem} = \text{Base Gravable} + \text{IVA}$$

### 4.4. Retenciones y Neto a Pagar (HU-15)
- **Principio Fundamental (Regla de Oro #6):** Las retenciones tributarias (ReteFuente, ReteICA, ReteIVA, Estampillas) **NO modifican el valor legal de la factura ni aparecen en el XML/PDF oficial ni en el asiento contable de facturación**. Afectan únicamente el recaudo (valor neto a pagar):
  $$\text{Neto a Pagar} = \text{Total Factura} - \sum \text{Retenciones Aplicables}$$
- **Resolución por Vigencias Fechadas:** Cada regla impositiva evalúa si la fecha de corte cae dentro de `[fechaInicio, fechaFin]` de la vigencia impositiva.
- **Bases de Cálculo:**
  - **ReteFuente:** Aplica sobre la suma de bases gravables si $\sum \text{Base Gravable} \ge \text{Base Mínima}$.
  - **ReteICA:** Aplica sobre la base gravable según el municipio de operación del cliente (ej. Pasto 7.00 ‰, Bogotá 9.66 ‰).
  - **ReteIVA:** Aplica sobre el **valor del IVA** acumulado de la factura ($\text{IVA} \times \text{Tarifa ReteIVA}$).
  - Si el cliente tiene `appliesWithholding = false`, todas las retenciones son $0 y $\text{Neto a Pagar} = \text{Total Factura}$.

### 4.5. Asiento Contable de Doble Partida (HU-16)
- Generado por `AccountingEntryGenerator` con estricta regla de cuadre contable:
  $$\sum \text{Débitos} = \sum \text{Créditos}$$
- **Composición del Asiento:**
  1. **Crédito (C) a Ingresos por cada sede:** Cuenta de ingresos (ej. `4105011501`) por el valor de la base gravable de cada sede.
  2. **Crédito (C) a IVA Generado:** Cuenta `2408010515` por el total del IVA liquidado (19%).
  3. **Débito (D) a Cartera Clientes:** Cuenta `1305051004` por el total legal de la factura.
- **Sin Retenciones:** El asiento de causación de venta no registra retenciones; estas se causan posteriormente en el módulo de recaudo/tesorería al pagar.
- **Protección contra descuadres:** Si $\sum \text{Débitos} \neq \sum \text{Créditos}$, el generador arroja de inmediato una excepción `UnbalancedAccountingEntryException` que aborta la operación.

### 4.6. Nomenclatura de Líneas Siigo (HU-18)
- Formato estructurado por `BillingCalculator.formatItemDescription()`:
  $$\text{MUNICIPIO.TIPO.SERVICIO.VELOCIDAD GR DD-DD MMM AAAA}$$
  *Ejemplo:* `PASTO.CORP.INTERNET DEDICADO 50M GR 01-30 SEP 2026`.

---

## 5. Pruebas Unitarias de Dominio y Cobertura

Suite ejecutada con JUnit 5 sin arranque de Spring:

| Clase de Prueba | Casos Cubiertos | Resultado |
|---|---|:---:|
| `BillingCalculatorTest` | - Resolución de fecha efectiva por certificación y observación (HU-10)<br>- Descripción Siigo formateada (HU-18)<br>- Factura consolidada canónica (3 sedes: 1 normal, 1 alta prorrateada, 1 cuota instalación)<br>- Aplicación de descuentos porcentuales (HU-13)<br>- Snapshot fiscal inmutable del cliente | **PASS** |
| `TaxCalculatorTest` | - IVA 19% en ítems gravados vs 0% en excluidos (HU-14)<br>- Cliente con `appliesWithholding=false` (retenciones = 0) (HU-15)<br>- Retenciones aplicadas según base mínima y vigencia fechada<br>- ReteICA territorial por municipio (Pasto vs Bogotá)<br>- ReteIVA aplicado sobre el valor del IVA y no sobre el total<br>- Clasificación de servicio (Internet vs Software)<br>- Invariante: valor total de factura inalterable por retenciones | **PASS** |
| `ProrationCalculatorTest` | - Alta a mitad de mes (cálculo sobre base 30 días)<br>- Alta el primer día del mes (sin prorrateo)<br>- Observación que anula el prorrateo | **PASS** |
| `AccountingEntryGeneratorTest` | - Cuadre estricto de doble partida ($\sum D = \sum C$)<br>- Asiento de venta sin líneas de retención<br>- Excepción lanzada ante cualquier desbalance | **PASS** |
| `MoneyTest` | - Precisión decimal con `BigDecimal`<br>- Suma, resta, multiplicación, porcentaje y redondeo `HALF_UP` | **PASS** |

---

## 6. Entregable del Sprint 1

- Modelo de dominio y Value Objects testeados e inmutables.
- Calculadoras de facturación, impuestos, prorrateos y generador contable 100% operativos.
- Todas las compuertas de cálculo puro validadas en verde.
