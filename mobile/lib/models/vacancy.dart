/// Вакансия для страницы сайта /vacancies (VacancyApiController). Три списка — текст, один пункт в строке.
class Vacancy {
  final int id;
  final String title;
  final String? summary;
  final String schedule;
  final String experience;
  final String? salary;
  final String? duties;
  final String? requirements;
  final String? conditions;
  final bool active;

  Vacancy({
    required this.id,
    required this.title,
    this.summary,
    required this.schedule,
    required this.experience,
    this.salary,
    this.duties,
    this.requirements,
    this.conditions,
    required this.active,
  });

  factory Vacancy.fromJson(Map<String, dynamic> json) => Vacancy(
        id: json['id'] as int,
        title: json['title'] as String,
        summary: json['summary'] as String?,
        schedule: json['schedule'] as String,
        experience: json['experience'] as String,
        salary: json['salary'] as String?,
        duties: json['duties'] as String?,
        requirements: json['requirements'] as String?,
        conditions: json['conditions'] as String?,
        active: json['active'] as bool,
      );
}

/// Отклик с сайта. vacancyId == null — кадровый резерв («Оставить номер»).
class VacancyReply {
  final int id;
  final int? vacancyId;
  final String? vacancyTitle;
  final String? name;
  final String phone;
  final String? about;
  final String status;
  final String? note;
  final DateTime createdAt;

  VacancyReply({
    required this.id,
    this.vacancyId,
    this.vacancyTitle,
    this.name,
    required this.phone,
    this.about,
    required this.status,
    this.note,
    required this.createdAt,
  });

  bool get isReserve => vacancyId == null;

  factory VacancyReply.fromJson(Map<String, dynamic> json) => VacancyReply(
        id: json['id'] as int,
        vacancyId: json['vacancyId'] as int?,
        vacancyTitle: json['vacancyTitle'] as String?,
        name: json['name'] as String?,
        phone: json['phone'] as String,
        about: json['about'] as String?,
        status: json['status'] as String,
        note: json['note'] as String?,
        createdAt: DateTime.parse(json['createdAt'] as String),
      );
}

/// Статусы отклика в порядке работы с ним.
const vacancyReplyStatuses = {
  'NEW': 'Новый',
  'CONTACTED': 'Связались',
  'INTERVIEW': 'Собеседование',
  'HIRED': 'Принят',
  'REJECTED': 'Отказ',
};
